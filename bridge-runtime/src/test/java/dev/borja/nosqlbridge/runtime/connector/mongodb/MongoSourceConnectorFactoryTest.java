package dev.borja.nosqlbridge.runtime.connector.mongodb;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.mongodb.client.MongoClient;

import dev.borja.nosqlbridge.runtime.plan.ConnectorPlan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class MongoSourceConnectorFactoryTest {
    @Test
    void declaresTechnologyWithoutResolvingClient() {
        ObjectProvider<MongoClient> provider = new ObjectProvider<>() {
            @Override
            public MongoClient getObject() {
                throw new AssertionError("Client must only be resolved by create(plan).");
            }
        };
        MongoSourceConnectorFactory factory = new MongoSourceConnectorFactory(provider,
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
        MongoSourceConnectorFactory factory = new MongoSourceConnectorFactory(provider,
                new MongoLogicalValueConverter());
        assertEquals(0, resolutions.get());

        assertInstanceOf(MongoSourceConnector.class, factory.create(new ConnectorPlan("mongodb",
                Map.of("database", "shop", "collection", "products"))));

        assertEquals(1, resolutions.get());
        verifyNoInteractions(client);
    }
}
