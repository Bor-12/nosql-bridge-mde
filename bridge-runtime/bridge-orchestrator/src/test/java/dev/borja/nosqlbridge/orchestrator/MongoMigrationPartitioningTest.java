package dev.borja.nosqlbridge.orchestrator;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Iterator;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.BsonObjectId;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import dev.borja.nosqlbridge.common.plan.BridgeExecutionPlan;
import dev.borja.nosqlbridge.common.plan.ConnectorPlan;
import dev.borja.nosqlbridge.common.registry.TechnologyRegistry;
import dev.borja.nosqlbridge.mongodb.MongoSourceTechnologyFactory;
import dev.borja.nosqlbridge.mongodb.MongoLogicalValueConverter;
import dev.borja.nosqlbridge.mongodb.MongoIdRange;
import dev.borja.nosqlbridge.common.work.MigrationId;
import dev.borja.nosqlbridge.common.work.WorkUnit;
import dev.borja.nosqlbridge.common.work.WorkUnitId;
import dev.borja.nosqlbridge.common.work.WorkUnitMessage;
import dev.borja.nosqlbridge.common.work.WorkUnitMessageSerializer;
import dev.borja.nosqlbridge.common.source.connector.RecordCursor;
import dev.borja.nosqlbridge.common.model.LogicalValue;
import dev.borja.nosqlbridge.mongodb.MongoSourceConnector;
import com.mongodb.client.model.Collation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class MongoMigrationPartitioningTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void workerReadsEveryIdOnceAfterWorkUnitMessageRoundTrip(boolean objectIds) {
        MongoClient client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        FindIterable<BsonDocument> discovery = mock(FindIterable.class);
        MongoCursor<BsonDocument> discoveryCursor = mock(MongoCursor.class);
        List<Object> ids = objectIds
                ? List.of(new ObjectId("000000000000000000000000"), new ObjectId("000000000000000000000001"),
                        new ObjectId("000000000000000000000003"), new ObjectId("ffffffffffffffffffffffff"))
                : List.of("", "000000000000000000000001", "TFG-E2E-001", "TFG-E2E-002", "TFG-E2E-003",
                        "\uE000", "\uD800\uDC00");
        Iterator<Object> discoveryIds = ids.iterator();
        when(client.getDatabase("shop")).thenReturn(database);
        when(database.getCollection("products")).thenReturn(collection);
        when(collection.find(BsonDocument.class)).thenReturn(discovery);
        when(discovery.projection(new Document("_id", 1))).thenReturn(discovery);
        when(discovery.sort(new Document("_id", 1))).thenReturn(discovery);
        when(discovery.hintString("_id_")).thenReturn(discovery);
        when(discovery.collation(Collation.builder().locale("simple").build())).thenReturn(discovery);
        when(discovery.iterator()).thenReturn(discoveryCursor);
        when(discoveryCursor.hasNext()).thenAnswer(invocation -> discoveryIds.hasNext());
        when(discoveryCursor.next()).thenAnswer(invocation -> new Document("_id", discoveryIds.next()).toBsonDocument());
        when(collection.find(any(Document.class))).thenAnswer(invocation -> {
            Document filter = invocation.getArgument(0);
            Document expression = filter.get("$expr", Document.class);
            List<Document> comparisons = expression.containsKey("$and")
                    ? expression.getList("$and", Document.class) : List.of(expression);
            Object upper = null;
            Object lower = null;
            for (Document comparison : comparisons) {
                String operation = comparison.containsKey("$lte") ? "$lte" : "$gt";
                List<?> operands = (List<?>) comparison.get(operation);
                BsonValue value = (BsonValue) ((Document) operands.get(1)).get("$literal");
                Object bound = value.isObjectId() ? value.asObjectId().getValue() : value.asString().getValue();
                if (operation.equals("$lte")) {
                    upper = bound;
                } else {
                    lower = bound;
                }
            }
            Object upperBound = upper;
            Object lowerBound = lower;
            assertEquals(objectIds ? ObjectId.class : String.class, upper.getClass());
            if (lower != null) {
                assertEquals(upper.getClass(), lower.getClass());
            }
            Iterator<Document> matching = ids.stream()
                    .filter(id -> compareIds(id, upperBound) <= 0 && (lowerBound == null || compareIds(id, lowerBound) > 0))
                    .map(id -> new Document("_id", id)).iterator();
            FindIterable<Document> results = mock(FindIterable.class);
            MongoCursor<Document> workerCursor = mock(MongoCursor.class);
            when(results.hintString("_id_")).thenReturn(results);
            when(results.collation(Collation.builder().locale("simple").build())).thenReturn(results);
            when(results.iterator()).thenReturn(workerCursor);
            when(workerCursor.hasNext()).thenAnswer(call -> matching.hasNext());
            when(workerCursor.next()).thenAnswer(call -> matching.next());
            return results;
        });
        ObjectProvider<MongoClient> provider = new ObjectProvider<>() {
            public MongoClient getObject() { return client; }
        };
        MongoLogicalValueConverter converter = new MongoLogicalValueConverter();
        TechnologyRegistry registry = new TechnologyRegistry(
                List.of(new MongoSourceTechnologyFactory(provider, converter)), List.of());
        ConnectorPlan source = new ConnectorPlan("mongodb", Map.of("database", "shop", "collection", "products"));
        BridgeExecutionPlan plan = new BridgeExecutionPlan(1, "test", source,
                new ConnectorPlan("dynamodb", Map.of("table", "Products", "partitionKey", "id")), "drop", "omit", List.of());
        WorkUnitMessageSerializer serializer = new WorkUnitMessageSerializer(new ObjectMapper());
        List<String> readIds = new ArrayList<>();
        MongoSourceConnector worker = new MongoSourceConnector(client, converter, source);

        try (WorkUnitCursor units = new MigrationPartitioner(registry).open(plan, new MigrationId(UUID.randomUUID()), 2)) {
            verifyNoInteractions(discoveryCursor);
            long sequence = 0;
            while (units.hasNext()) {
                WorkUnit unit = units.next();
                assertEquals(new WorkUnitId(sequence++), unit.workUnitId());
                WorkUnitMessage received = serializer.deserialize(serializer.serialize(new WorkUnitMessage(unit, plan)));
                assertEquals(unit, received.workUnit());
                try (RecordCursor records = worker.open(received.workUnit().sourcePartition())) {
                    records.forEachRemaining(record -> readIds.add(((LogicalValue.StringValue) record.get("_id")).value()));
                }
            }
        }

        assertEquals(ids.stream().map(Object::toString).toList(), readIds);
        assertEquals(ids.size(), readIds.stream().distinct().count());
        verify(discoveryCursor).close();
    }

    private static int compareIds(Object left, Object right) {
        if (left instanceof ObjectId objectId) {
            return objectId.compareTo((ObjectId) right);
        }
        return Arrays.compareUnsigned(((String) left).getBytes(StandardCharsets.UTF_8),
                ((String) right).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolvesIncrementalMongoPlanningThroughExistingRegistry() {
        MongoClient client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        FindIterable<BsonDocument> documents = mock(FindIterable.class);
        MongoCursor<BsonDocument> cursor = mock(MongoCursor.class);
        ObjectProvider<MongoClient> provider = new ObjectProvider<>() {
            public MongoClient getObject() { return client; }
        };
        MongoSourceTechnologyFactory factory = new MongoSourceTechnologyFactory(provider, new MongoLogicalValueConverter());
        TechnologyRegistry registry = new TechnologyRegistry(List.of(factory), List.of());
        ConnectorPlan source = new ConnectorPlan("mongodb", Map.of("database", "shop", "collection", "products"));
        BridgeExecutionPlan plan = new BridgeExecutionPlan(1, "test", source,
                new ConnectorPlan("unused-target", Map.of()), "drop", "omit", List.of());
        MigrationId migrationId = new MigrationId(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
        ObjectId first = new ObjectId("000000000000000000000001");
        ObjectId second = new ObjectId("000000000000000000000002");
        when(client.getDatabase("shop")).thenReturn(database);
        when(database.getCollection("products")).thenReturn(collection);
        when(collection.find(BsonDocument.class)).thenReturn(documents);
        when(documents.projection(new Document("_id", 1))).thenReturn(documents);
        when(documents.sort(new Document("_id", 1))).thenReturn(documents);
        when(documents.hintString("_id_")).thenReturn(documents);
        when(documents.collation(com.mongodb.client.model.Collation.builder().locale("simple").build())).thenReturn(documents);
        when(documents.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true);
        when(cursor.next()).thenReturn(new BsonDocument("_id", new BsonObjectId(first)), new BsonDocument("_id", new BsonObjectId(second)));

        try (WorkUnitCursor units = new MigrationPartitioner(registry).open(plan, migrationId, 2)) {
            verifyNoInteractions(cursor);
            assertEquals(new WorkUnit(migrationId, new WorkUnitId(0),
                    MongoIdRange.fromBson(null, new BsonObjectId(second)).toSourcePartition()), units.next());
            verify(cursor, times(2)).next();
            verify(cursor, never()).close();
        }

        verify(cursor).close();
        verify(client, never()).close();
    }

}
