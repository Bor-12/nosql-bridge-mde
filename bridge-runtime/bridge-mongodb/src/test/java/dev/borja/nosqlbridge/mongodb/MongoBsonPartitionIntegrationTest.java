package dev.borja.nosqlbridge.mongodb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Collation;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonObjectId;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.RawBsonDocument;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

import dev.borja.nosqlbridge.common.model.LogicalValue;
import dev.borja.nosqlbridge.common.plan.BridgeExecutionPlan;
import dev.borja.nosqlbridge.common.plan.ConnectorPlan;
import dev.borja.nosqlbridge.common.source.connector.RecordCursor;
import dev.borja.nosqlbridge.common.source.partitioning.SourcePartitionCursor;
import dev.borja.nosqlbridge.common.work.MigrationId;
import dev.borja.nosqlbridge.common.work.SourcePartition;
import dev.borja.nosqlbridge.common.work.WorkUnit;
import dev.borja.nosqlbridge.common.work.WorkUnitId;
import dev.borja.nosqlbridge.common.work.WorkUnitMessage;
import dev.borja.nosqlbridge.common.work.WorkUnitMessageSerializer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledIfEnvironmentVariable(named = "BRIDGE_MONGODB_TEST_URI", matches = ".+")
class MongoBsonPartitionIntegrationTest {

    @ParameterizedTest
    @CsvSource({"string, 0, 3", "string, 2, 3", "string, 6, 3", "string, 7, 3", "string, 7, 1",
            "objectId, 7, 3", "mixed, 10, 3", "mixed, 10, 1", "mixed, 10000, 1000", "bson, 15, 4", "bson, 15, 1"})
    void serializedPartitionsReadExactlyTheirExpectedDocuments(String kind, int count, int size) {
        String collectionName = "partition_" + UUID.randomUUID().toString().replace("-", "");
        try (MongoClient client = MongoClients.create(System.getenv("BRIDGE_MONGODB_TEST_URI"))) {
            MongoCollection<BsonDocument> raw = client.getDatabase("nosql_bridge_partition_tests")
                    .getCollection(collectionName, BsonDocument.class);
            MongoCollection<Document> collection = raw.withDocumentClass(Document.class);
            try {
                List<BsonValue> ids = ids(kind, count);
                List<BsonDocument> input = new ArrayList<>();
                for (int index = 0; index < ids.size(); index++) {
                    input.add(new BsonDocument("_id", ids.get(index)).append("ordinal", new BsonInt32(index)));
                }
                Collections.reverse(input);
                if (!input.isEmpty()) {
                    raw.insertMany(input);
                }
                List<BsonDocument> ordered = raw.find().sort(new Document("_id", 1))
                        .collation(Collation.builder().locale("simple").build()).into(new ArrayList<>());
                ConnectorPlan source = new ConnectorPlan("mongodb", Map.of("database", "nosql_bridge_partition_tests", "collection", collectionName));
                BridgeExecutionPlan plan = new BridgeExecutionPlan(1, "test", source,
                        new ConnectorPlan("dynamodb", Map.of("table", "unused", "partitionKey", "id")), "drop", "omit", List.of());
                WorkUnitMessageSerializer serializer = new WorkUnitMessageSerializer(new ObjectMapper());
                MongoSourceConnector worker = new MongoSourceConnector(client, new MongoLogicalValueConverter(), source);
                List<Long> allRead = new ArrayList<>();
                int partitions = 0;
                BsonValue previous = null;
                try (SourcePartitionCursor cursor = new MongoSourcePartitioner(collection).open(size)) {
                    while (cursor.hasNext()) {
                        WorkUnitMessage message = new WorkUnitMessage(new WorkUnit(new MigrationId(UUID.randomUUID()),
                                new WorkUnitId(partitions), cursor.next()), plan);
                        WorkUnitMessage restored = serializer.deserialize(serializer.serialize(message));
                        assertEquals(message, restored);
                        MongoIdRange range = MongoIdRange.fromSourcePartition(restored.workUnit().sourcePartition());
                        assertEquals(previous == null ? null : bsonBytes(previous),
                                range.lowerExclusive() == null ? null : bsonBytes(BsonDocument.parse(range.lowerExclusive()).get("value")));
                        int start = partitions * size;
                        int end = Math.min(start + size, count);
                        previous = ordered.get(end - 1).get("_id");
                        assertEquals(bsonBytes(previous), bsonBytes(BsonDocument.parse(range.upperInclusive()).get("value")));
                        List<Long> actual = new ArrayList<>();
                        try (RecordCursor records = worker.open(restored.workUnit().sourcePartition())) {
                            records.forEachRemaining(record -> actual.add(((LogicalValue.IntegerValue) record.get("ordinal")).value()));
                        }
                        List<Long> expected = ordered.subList(start, end).stream().map(document -> (long) document.getInt32("ordinal").getValue()).toList();
                        assertEquals(end - start, actual.size());
                        assertEquals(new HashSet<>(expected), new HashSet<>(actual));
                        allRead.addAll(actual);
                        partitions++;
                    }
                    assertFalse(cursor.hasNext());
                }
                assertEquals((count + size - 1) / size, partitions);
                assertEquals(count, allRead.size());
                assertEquals(count, new HashSet<>(allRead).size());
                if (kind.equals("mixed") && count == 10000) {
                    MongoIdRange range = MongoIdRange.fromBson(ordered.get(4499).get("_id"), ordered.get(5499).get("_id"));
                    Document explain = collection.find(range.toFilter()).hintString("_id_")
                            .collation(Collation.builder().locale("simple").build())
                            .explain(com.mongodb.ExplainVerbosity.EXECUTION_STATS);
                    Document statistics = explain.get("executionStats", Document.class);
                    assertEquals(1000, ((Number) statistics.get("nReturned")).intValue());
                    assertTrue(explain.toJson().contains("IXSCAN"));
                    System.out.println("MongoDB BSON range explain: " + statistics.toJson());
                }
            } finally {
                raw.drop();
            }
        }
    }

    @Test
    void rejectsMalformedDescriptorsWithEmptyAndPopulatedCollections() {
        String collectionName = "partition_" + UUID.randomUUID().toString().replace("-", "");
        try (MongoClient client = MongoClients.create(System.getenv("BRIDGE_MONGODB_TEST_URI"))) {
            MongoCollection<BsonDocument> collection = client.getDatabase("nosql_bridge_partition_tests")
                    .getCollection(collectionName, BsonDocument.class);
            ConnectorPlan source = new ConnectorPlan("mongodb", Map.of("database", "nosql_bridge_partition_tests", "collection", collectionName));
            MongoSourceConnector worker = new MongoSourceConnector(client, new MongoLogicalValueConverter(), source);
            List<Map<String, String>> descriptors = List.of(
                    Map.of(),
                    Map.of("upperInclusive", "broken"),
                    Map.of("upperInclusive", "{value: []}"),
                    Map.of("upperInclusive", "{value: {$regularExpression: {pattern: 'x', options: ''}}}"),
                    Map.of("upperInclusive", "{value: {$undefined: true}}"),
                    Map.of("upperInclusive", "{value: 1}", "lowerExclusive", "{}"),
                    Map.of("upperInclusive", "{value: 1}", "extra", "unexpected"));
            try {
                for (int documents : List.of(0, 1)) {
                    if (documents > 0) {
                        collection.insertOne(BsonDocument.parse("{_id: 0}"));
                    }
                    for (Map<String, String> parameters : descriptors) {
                        SourcePartition partition = new SourcePartition(MongoIdRange.BSON_TYPE, parameters);
                        assertThrows(IllegalArgumentException.class, () -> worker.open(partition));
                    }
                }
            } finally {
                collection.drop();
            }
        }
    }

    private java.nio.ByteBuffer bsonBytes(BsonValue value) {
        return new RawBsonDocument(new BsonDocument("value", value), new BsonDocumentCodec()).getByteBuffer().asNIO();
    }

    private List<BsonValue> ids(String kind, int count) {
        if (kind.equals("bson")) {
            return new ArrayList<>(BsonDocument.parse("{a: null, b: {$numberInt: '1'}, c: {$numberLong: '2'}, "
                    + "d: {$numberDouble: '3.5'}, e: {$numberDecimal: '4.50'}, f: '', g: '$literal', h: 'é', "
                    + "i: {a: 1, b: 2}, j: {b: 2, a: 1}, k: {$binary: {base64: 'AQID', subType: '80'}}, "
                    + "l: {$oid: '000000000000000000000001'}, m: false, n: true, o: {$date: {$numberLong: '123'}}}").values());
        }
        List<BsonValue> ids = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            boolean objectId = kind.equals("objectId") || (kind.equals("mixed") && index >= count / 2);
            ids.add(objectId ? new BsonObjectId(new ObjectId(String.format("%024x", index)))
                    : new BsonString(String.format("TFG-E2E-%05d", index)));
        }
        return ids;
    }
}
