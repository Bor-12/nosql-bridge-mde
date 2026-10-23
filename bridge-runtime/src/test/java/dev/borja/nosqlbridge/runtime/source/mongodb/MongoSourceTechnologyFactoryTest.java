package dev.borja.nosqlbridge.runtime.source.mongodb;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import dev.borja.nosqlbridge.runtime.plan.BridgeExecutionPlan;
import dev.borja.nosqlbridge.runtime.plan.ConnectorPlan;
import dev.borja.nosqlbridge.runtime.registry.TechnologyRegistry;
import dev.borja.nosqlbridge.runtime.orchestration.MigrationPartitioner;
import dev.borja.nosqlbridge.runtime.orchestration.WorkUnitCursor;
import dev.borja.nosqlbridge.runtime.work.MigrationId;
import dev.borja.nosqlbridge.runtime.work.WorkUnit;
import dev.borja.nosqlbridge.runtime.work.WorkUnitId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MongoSourceTechnologyFactoryTest {
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

    @Test
    void rejectsInvalidPartitionerConfigurationBeforeResolvingClient() {
        ObjectProvider<MongoClient> provider = new ObjectProvider<>() {
            public MongoClient getObject() { throw new AssertionError("Must validate before resolving client."); }
        };
        MongoSourceTechnologyFactory factory = new MongoSourceTechnologyFactory(provider, new MongoLogicalValueConverter());

        assertThrows(IllegalArgumentException.class, () -> factory.createPartitioner(null));
        assertThrows(IllegalArgumentException.class,
                () -> factory.createPartitioner(new ConnectorPlan("other-source", Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> factory.createPartitioner(new ConnectorPlan("mongodb", null)));
        assertThrows(IllegalArgumentException.class,
                () -> factory.createPartitioner(new ConnectorPlan("mongodb", Map.of("collection", "products"))));
        assertThrows(IllegalArgumentException.class,
                () -> factory.createPartitioner(new ConnectorPlan("mongodb", Map.of("database", " ", "collection", "products"))));
        assertThrows(IllegalArgumentException.class,
                () -> factory.createPartitioner(new ConnectorPlan("mongodb", Map.of("database", "shop"))));
        assertThrows(IllegalArgumentException.class,
                () -> factory.createPartitioner(new ConnectorPlan("mongodb", Map.of("database", "shop", "collection", ""))));
    }

    @Test
    void declaresTechnologyWithoutResolvingClient() {
        ObjectProvider<MongoClient> provider = new ObjectProvider<>() {
            @Override
            public MongoClient getObject() {
                throw new AssertionError("Client must only be resolved by createConnector(plan).");
            }
        };
        MongoSourceTechnologyFactory factory = new MongoSourceTechnologyFactory(provider,
                new MongoLogicalValueConverter());

        assertEquals("mongodb", factory.technology());
    }

    @Test
    void createsConnectorWithDeferredClientWithoutDatabaseOperations() {
        MongoClient client = mock(MongoClient.class);
        AtomicInteger resolutions = new AtomicInteger();
        ObjectProvider<MongoClient> provider = new ObjectProvider<>() {
            @Override
            public MongoClient getObject() {
                resolutions.incrementAndGet();
                return client;
            }
        };
        MongoSourceTechnologyFactory factory = new MongoSourceTechnologyFactory(provider,
                new MongoLogicalValueConverter());
        assertEquals(0, resolutions.get());

        assertInstanceOf(MongoSourceConnector.class, factory.createConnector(new ConnectorPlan("mongodb",
                Map.of("database", "shop", "collection", "products"))));

        assertEquals(1, resolutions.get());
        verifyNoInteractions(client);
    }
}
