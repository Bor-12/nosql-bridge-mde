package dev.borja.nosqlbridge.worker.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import dev.borja.nosqlbridge.common.plan.BridgeExecutionPlan;
import dev.borja.nosqlbridge.common.plan.ConnectorPlan;
import dev.borja.nosqlbridge.common.queue.WorkQueue;
import dev.borja.nosqlbridge.common.queue.WorkQueue.Delivery;
import dev.borja.nosqlbridge.common.registry.TechnologyRegistry;
import dev.borja.nosqlbridge.common.source.SourceTechnologyFactory;
import dev.borja.nosqlbridge.common.target.TargetConnectorFactory;
import dev.borja.nosqlbridge.common.work.MigrationId;
import dev.borja.nosqlbridge.common.work.SourcePartition;
import dev.borja.nosqlbridge.common.work.WorkUnit;
import dev.borja.nosqlbridge.common.work.WorkUnitId;
import dev.borja.nosqlbridge.common.work.WorkUnitMessage;
import dev.borja.nosqlbridge.dynamodb.DynamoTargetConnector;
import dev.borja.nosqlbridge.mongodb.MongoSourceConnector;
import dev.borja.nosqlbridge.sqs.SqsWorkQueue;
import dev.borja.nosqlbridge.worker.BridgeRuntimeApplication;
import dev.borja.nosqlbridge.worker.WorkerConsumer;
import dev.borja.nosqlbridge.worker.execution.WorkUnitExecutor;
import dev.borja.nosqlbridge.worker.runner.PlanRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConnectorAvailabilityTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(BridgeRuntimeApplication.class);
    private final ConnectorPlan sourcePlan = new ConnectorPlan("mongodb",
            Map.of("database", "shop", "collection", "products"));
    private final ConnectorPlan targetPlan = new ConnectorPlan("dynamodb",
            Map.of("table", "Products", "partitionKey", "id"));

    @Test
    void startsWorkerWithOnlySqsAndRegistersFactoriesWithoutCreatingDatabaseClients() {
        SqsClient sqsClient = mock(SqsClient.class);
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().build());
        try (MockedStatic<MongoClients> mongoClients = mockStatic(MongoClients.class);
                MockedStatic<DynamoDbClient> dynamoClients = mockStatic(DynamoDbClient.class)) {
            contextRunner.withBean(SqsClient.class, () -> sqsClient)
                    .withPropertyValues("bridge.runtime.mode=worker",
                            "bridge.sqs.queue-url=https://sqs.eu-west-1.amazonaws.com/123456789012/work")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        assertEquals(1, context.getBeansOfType(WorkerConsumer.class).size());
                        assertInstanceOf(SqsWorkQueue.class, context.getBean(WorkQueue.class));
                        assertTrue(context.getBeansOfType(PlanRunner.class).isEmpty());
                        TechnologyRegistry registry = context.getBean(TechnologyRegistry.class);
                        assertSame(context.getBean(SourceTechnologyFactory.class), registry.sourceFactory("mongodb"));
                        assertSame(context.getBean(TargetConnectorFactory.class), registry.targetFactory("dynamodb"));
                        mongoClients.verifyNoInteractions();
                        dynamoClients.verifyNoInteractions();
                    });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"mongodb", "dynamodb"})
    void selectsInstalledConnectorWithInjectedClientWithoutRequiringUnusedTechnology(String technology) {
        MongoClient mongoClient = mock(MongoClient.class);
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        ApplicationContextRunner configured = "mongodb".equals(technology)
                ? contextRunner.withBean(MongoClient.class, () -> mongoClient)
                : contextRunner.withBean(DynamoDbClient.class, () -> dynamoDbClient);

        configured.run(context -> {
            assertNull(context.getStartupFailure());
            TechnologyRegistry registry = context.getBean(TechnologyRegistry.class);
            for (int index = 0; index < 2; index++) {
                if ("mongodb".equals(technology)) {
                    assertInstanceOf(MongoSourceConnector.class, registry.sourceFactory(technology).createConnector(sourcePlan));
                    RuntimeException failure = assertThrows(RuntimeException.class,
                            () -> registry.targetFactory("dynamodb").create(targetPlan));
                    assertTrue(NestedExceptionUtils.getMostSpecificCause(failure).getMessage().contains("bridge.dynamodb.region"));
                } else {
                    assertInstanceOf(DynamoTargetConnector.class, registry.targetFactory(technology).create(targetPlan));
                    RuntimeException failure = assertThrows(RuntimeException.class,
                            () -> registry.sourceFactory("mongodb").createConnector(sourcePlan));
                    assertTrue(NestedExceptionUtils.getMostSpecificCause(failure).getMessage().contains("bridge.mongodb.uri"));
                }
            }
            verifyNoInteractions(mongoClient, dynamoDbClient);
        });
    }

    @Test
    void distinguishesUnsupportedTechnologyAndRoleFromMissingConfiguration() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            TechnologyRegistry registry = context.getBean(TechnologyRegistry.class);
            assertEquals("Unsupported source technology: unknown", assertThrows(IllegalArgumentException.class,
                    () -> registry.sourceFactory("unknown")).getMessage());
            assertEquals("Unsupported target technology: unknown", assertThrows(IllegalArgumentException.class,
                    () -> registry.targetFactory("unknown")).getMessage());
            assertEquals("Unsupported source technology: dynamodb", assertThrows(IllegalArgumentException.class,
                    () -> registry.sourceFactory("dynamodb")).getMessage());
            assertEquals("Unsupported target technology: mongodb", assertThrows(IllegalArgumentException.class,
                    () -> registry.targetFactory("mongodb")).getMessage());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"mongodb", "dynamodb"})
    void doesNotAcknowledgeDeliveryWhenRequiredConnectorConfigurationIsMissing(String technology) {
        WorkQueue queue = mock(WorkQueue.class);
        WorkUnit unit = new WorkUnit(new MigrationId(UUID.fromString("550e8400-e29b-41d4-a716-446655440000")),
                new WorkUnitId(0), new SourcePartition("test", Map.of()));
        BridgeExecutionPlan plan = new BridgeExecutionPlan(1, "test", sourcePlan, targetPlan, "preserve", "omit", List.of());
        Delivery delivery = new Delivery(new WorkUnitMessage(unit, plan), "receipt");
        when(queue.receive()).thenReturn(Optional.of(delivery));
        ApplicationContextRunner configured = contextRunner.withBean(WorkQueue.class, () -> queue);
        if ("dynamodb".equals(technology)) {
            configured = configured.withBean(MongoClient.class, () -> mock(MongoClient.class));
        }

        configured.run(context -> {
            assertNull(context.getStartupFailure());
            WorkerConsumer consumer = new WorkerConsumer(queue, context.getBean(WorkUnitExecutor.class));
            RuntimeException failure = assertThrows(RuntimeException.class, consumer::processOne);
            String property = "mongodb".equals(technology) ? "bridge.mongodb.uri" : "bridge.dynamodb.region";
            assertTrue(NestedExceptionUtils.getMostSpecificCause(failure).getMessage().contains(property));
            verify(queue).receive();
            verify(queue, never()).acknowledge(any(Delivery.class));
        });
    }
}
