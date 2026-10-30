package dev.borja.nosqlbridge.mongodb;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.mongodb.client.MongoClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import dev.borja.nosqlbridge.common.plan.ConnectorPlan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class MongoSourceTechnologyFactoryTest {
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
