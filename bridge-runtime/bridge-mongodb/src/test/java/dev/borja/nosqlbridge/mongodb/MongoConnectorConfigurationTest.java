package dev.borja.nosqlbridge.mongodb;

import java.util.Map;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;

import dev.borja.nosqlbridge.common.source.SourceTechnologyFactory;
import dev.borja.nosqlbridge.common.plan.ConnectorPlan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class MongoConnectorConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MongoConnectorConfiguration.class);

    @Test
    void requiresMongoUriOnlyWhenConnectorIsRequested() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> context.getBean(SourceTechnologyFactory.class).createConnector(new ConnectorPlan("mongodb",
                            Map.of("database", "shop", "collection", "products"))));
            assertTrue(NestedExceptionUtils.getMostSpecificCause(failure).getMessage().contains("bridge.mongodb.uri"));
        });
        contextRunner.withPropertyValues("bridge.mongodb.uri= ")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    RuntimeException failure = assertThrows(RuntimeException.class,
                            () -> context.getBean(MongoClient.class));
                    assertTrue(NestedExceptionUtils.getMostSpecificCause(failure).getMessage().contains("bridge.mongodb.uri"));
                });
    }

    @Test
    void registersDefaultBeansAndCreatesClientFromUriOnlyWhenRequestedThenClosesIt() {
        MongoClient client = mock(MongoClient.class);
        String uri = "mongodb://localhost:27018";
        try (MockedStatic<MongoClients> clients = mockStatic(MongoClients.class)) {
            clients.when(() -> MongoClients.create(uri)).thenReturn(client);

            contextRunner.withPropertyValues("bridge.mongodb.uri=" + uri).run(context -> {
                assertNull(context.getStartupFailure());
                assertEquals(1, context.getBeansOfType(MongoLogicalValueConverter.class).size());
                assertEquals(1, context.getBeansOfType(MongoSourceTechnologyFactory.class).size());
                assertSame(context.getBean(MongoSourceTechnologyFactory.class),
                        context.getBean(SourceTechnologyFactory.class));
                clients.verifyNoInteractions();

                assertEquals(1, context.getBeansOfType(MongoClient.class).size());
                assertSame(client, context.getBean(MongoClient.class));
                clients.verify(() -> MongoClients.create(uri));
                verifyNoInteractions(client);
            });

            clients.verifyNoMoreInteractions();
            verify(client).close();
        }
    }

    @Test
    void usesAlternativeClientAndConverterWithoutDuplicatesOrConnectionProperties() {
        MongoClient client = mock(MongoClient.class);
        MongoLogicalValueConverter converter = new MongoLogicalValueConverter();

        try (MockedStatic<MongoClients> clients = mockStatic(MongoClients.class)) {
            contextRunner.withBean(MongoClient.class, () -> client)
                    .withBean(MongoLogicalValueConverter.class, () -> converter)
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        assertEquals(1, context.getBeansOfType(MongoClient.class).size());
                        assertEquals(1, context.getBeansOfType(MongoLogicalValueConverter.class).size());
                        assertEquals(1, context.getBeansOfType(MongoSourceTechnologyFactory.class).size());
                        assertSame(client, context.getBean(MongoClient.class));
                        assertSame(converter, context.getBean(MongoLogicalValueConverter.class));
                        verifyNoInteractions(client);
                    });

            clients.verifyNoInteractions();
        }
    }
}
