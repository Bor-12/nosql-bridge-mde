package dev.borja.nosqlbridge.runtime.target.dynamodb;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import dev.borja.nosqlbridge.runtime.plan.ConnectorPlan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DynamoTargetConnectorFactoryTest {
    @Test
    void declaresTechnologyWithoutResolvingClient() {
        ObjectProvider<DynamoDbClient> provider = new ObjectProvider<>() {
            @Override
            public DynamoDbClient getObject() {
                throw new AssertionError("Client must only be resolved by create(plan).");
            }
        };
        DynamoTargetConnectorFactory factory = new DynamoTargetConnectorFactory(provider,
                new DynamoLogicalValueConverter());

        assertEquals("dynamodb", factory.technology());
    }

    @Test
    void createsConnectorWithDeferredClientWithoutDatabaseOperations() {
        DynamoDbClient client = mock(DynamoDbClient.class);
        AtomicInteger resolutions = new AtomicInteger();
        ObjectProvider<DynamoDbClient> provider = new ObjectProvider<>() {
            @Override
            public DynamoDbClient getObject() {
                resolutions.incrementAndGet();
                return client;
            }
        };
        DynamoTargetConnectorFactory factory = new DynamoTargetConnectorFactory(provider,
                new DynamoLogicalValueConverter());
        assertEquals(0, resolutions.get());

        assertInstanceOf(DynamoTargetConnector.class, factory.create(new ConnectorPlan("dynamodb",
                Map.of("table", "Products", "partitionKey", "id"))));

        assertEquals(1, resolutions.get());
        verifyNoInteractions(client);
    }
}
