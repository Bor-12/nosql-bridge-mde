package dev.borja.nosqlbridge.orchestrator;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import dev.borja.nosqlbridge.common.plan.BridgeExecutionPlan;
import dev.borja.nosqlbridge.common.plan.ConnectorPlan;
import dev.borja.nosqlbridge.common.registry.TechnologyRegistry;
import dev.borja.nosqlbridge.mongodb.MongoSourceTechnologyFactory;
import dev.borja.nosqlbridge.mongodb.MongoLogicalValueConverter;
import dev.borja.nosqlbridge.mongodb.MongoIdRange;
import dev.borja.nosqlbridge.common.work.MigrationId;
import dev.borja.nosqlbridge.common.work.WorkUnit;
import dev.borja.nosqlbridge.common.work.WorkUnitId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MongoMigrationPartitioningTest {
    @Test
    @SuppressWarnings("unchecked")
    void resolvesIncrementalMongoPlanningThroughExistingRegistry() {
        MongoClient client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        FindIterable<Document> documents = mock(FindIterable.class);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
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
        when(collection.find()).thenReturn(documents);
        when(documents.projection(new Document("_id", 1))).thenReturn(documents);
        when(documents.sort(new Document("_id", 1))).thenReturn(documents);
        when(documents.hintString("_id_")).thenReturn(documents);
        when(documents.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true);
        when(cursor.next()).thenReturn(new Document("_id", first), new Document("_id", second));

        try (WorkUnitCursor units = new MigrationPartitioner(registry).open(plan, migrationId, 2)) {
            verifyNoInteractions(cursor);
            assertEquals(new WorkUnit(migrationId, new WorkUnitId(0),
                    new MongoIdRange(null, second.toHexString()).toSourcePartition()), units.next());
            verify(cursor, times(2)).next();
            verify(cursor, never()).close();
        }

        verify(cursor).close();
        verify(client, never()).close();
    }

}
