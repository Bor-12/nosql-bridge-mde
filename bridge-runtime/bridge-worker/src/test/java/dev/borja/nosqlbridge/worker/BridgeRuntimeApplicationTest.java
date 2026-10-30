package dev.borja.nosqlbridge.worker;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicReference;

import com.mongodb.client.MongoClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import dev.borja.nosqlbridge.worker.config.ExecutionConfiguration;
import dev.borja.nosqlbridge.worker.config.WorkerConfiguration;
import dev.borja.nosqlbridge.common.queue.WorkQueue;
import dev.borja.nosqlbridge.sqs.SqsWorkQueue;
import dev.borja.nosqlbridge.worker.runner.PlanRunner;
import dev.borja.nosqlbridge.common.registry.TechnologyRegistry;
import dev.borja.nosqlbridge.mongodb.MongoSourceTechnologyFactory;
import dev.borja.nosqlbridge.dynamodb.DynamoTargetConnectorFactory;
import dev.borja.nosqlbridge.common.plan.ConnectorPlan;
import dev.borja.nosqlbridge.common.source.SourceTechnologyFactory;
import dev.borja.nosqlbridge.common.source.connector.RecordCursor;
import dev.borja.nosqlbridge.common.source.connector.SourceConnector;
import dev.borja.nosqlbridge.mongodb.MongoProperties;
import dev.borja.nosqlbridge.common.target.TargetConnector;
import dev.borja.nosqlbridge.common.target.TargetConnectorFactory;
import dev.borja.nosqlbridge.dynamodb.DynamoProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BridgeRuntimeApplicationTest {

    @TempDir
    Path directory;

    private final ByteArrayOutputStream errors = new ByteArrayOutputStream();
    private final PrintStream error = new PrintStream(errors, true, StandardCharsets.UTF_8);
    private final SourceConnector source = mock(SourceConnector.class);
    private final TargetConnector target = mock(TargetConnector.class);

    @Test
    void startsRealWorkerWithoutPlanAndOverridesContradictoryYamlMode() throws Exception {
        Path config = workerConfig();
        SpringApplication application = new SpringApplication(BridgeRuntimeApplication.class);
        SqsClient sqsClient = mock(SqsClient.class);
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().build());
        MongoClient mongoClient = mock(MongoClient.class);
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        AtomicReference<ConfigurableApplicationContext> started = new AtomicReference<>();
        application.addInitializers(context -> {
            started.set(context);
            context.getBeanFactory().registerSingleton("sqsClient", sqsClient);
            context.getBeanFactory().registerSingleton("mongoClient", mongoClient);
            context.getBeanFactory().registerSingleton("dynamoDbClient", dynamoDbClient);
        });
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        try {
            assertEquals(0, BridgeRuntimeApplication.run(application, error,
                    "--mode", "worker", "--config", config.toString()));
            assertTrue(started.get().isActive());
            assertEquals(1, started.get().getBeansOfType(WorkerConsumer.class).size());
            assertEquals(1, started.get().getBeansOfType(WorkQueue.class).size());
            assertInstanceOf(SqsWorkQueue.class, started.get().getBean(WorkQueue.class));
            assertEquals(1, started.get().getBeansOfType(TechnologyRegistry.class).size());
            TechnologyRegistry registry = started.get().getBean(TechnologyRegistry.class);
            assertSame(started.get().getBean(MongoSourceTechnologyFactory.class), registry.sourceFactory("mongodb"));
            assertSame(started.get().getBean(DynamoTargetConnectorFactory.class), registry.targetFactory("dynamodb"));
            verifyNoInteractions(mongoClient, dynamoDbClient);
            assertTrue(started.get().getBeansOfType(PlanRunner.class).isEmpty());
            assertEquals("worker", started.get().getEnvironment().getProperty("bridge.runtime.mode"));
            assertEquals("bridge-runtime", started.get().getEnvironment().getProperty("spring.application.name"));
            assertEquals("mongodb://localhost:27019", started.get().getBean(MongoProperties.class).uri());
            assertEquals("eu-west-1", started.get().getBean(DynamoProperties.class).region());
            assertEquals("https://sqs.eu-west-1.amazonaws.com/123456789012/work",
                    started.get().getEnvironment().getProperty("bridge.sqs.queue-url"));
        } finally {
            if (started.get() != null) {
                started.get().close();
            }
        }
    }

    @Test
    void translatesWorkerOptionsInEitherOrderAndKeepsTheSpringContextOpen() throws Exception {
        Path config = workerConfig();
        for (String[] args : List.of(
                new String[] {"--mode", "worker", "--config", config.toString()},
                new String[] {"--config", config.toString(), "--mode", "worker"})) {
            SpringApplication application = mock(SpringApplication.class);
            ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
            when(application.run("--bridge.runtime.mode=worker",
                    "--spring.config.location=optional:classpath:/application.yaml," + config.toUri())).thenReturn(context);

            assertEquals(0, BridgeRuntimeApplication.run(application, error, args));

            verify(application).run("--bridge.runtime.mode=worker",
                    "--spring.config.location=optional:classpath:/application.yaml," + config.toUri());
            verify(context, never()).close();
        }
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
    }

    @Test
    void reportsWorkerStartupFailure() throws Exception {
        Path config = workerConfig();
        SpringApplication application = mock(SpringApplication.class);
        when(application.run("--bridge.runtime.mode=worker",
                "--spring.config.location=optional:classpath:/application.yaml," + config.toUri()))
                .thenThrow(new IllegalStateException("Worker mode requires bridge.sqs.queue-url or a WorkQueue bean."));

        assertEquals(1, BridgeRuntimeApplication.run(application, error,
                "--mode", "worker", "--config", config.toString()));

        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("bridge.sqs.queue-url"));
    }

    @Test
    void rejectsInvalidWorkerArgumentsBeforeStartingSpring() throws Exception {
        Path config = workerConfig();
        for (String[] args : List.of(
                new String[] {"--mode", "worker"},
                new String[] {"--mode", "worker", "--config"},
                new String[] {"--mode", "worker", "--config", "missing.yml"},
                new String[] {"--mode", "worker", "--config", directory.toString()},
                new String[] {"--mode", "worker", "--config", config.toString(), "--plan", "plan.json"},
                new String[] {"--mode", "worker", "--mode", "worker", "--config", config.toString()},
                new String[] {"--mode", "worker", "--config", config.toString(), "--config", config.toString()},
                new String[] {"--mode", "orchestrator", "--config", config.toString()},
                new String[] {"--mode", "worker", "--config", config.toString(), "--unknown", "value"},
                new String[] {"--mode", "worker", "--config", config.toString(), "--bridge.sqs.region=eu-west-1"})) {
            SpringApplication application = mock(SpringApplication.class);
            assertEquals(1, BridgeRuntimeApplication.run(application, error, args));
            verifyNoInteractions(application);
        }
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("Worker mode does not accept --plan."));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("--mode worker --config <path>"));
    }

    static Stream<Arguments> invalidArguments() {
        return List.of(new String[] {}, new String[] { "--plan" },
                new String[] { "--plan", "" }, new String[] { "--plan", "   " },
                new String[] { "--plan", "--other" },
                new String[] { "--bridge.execution.plan=plan.json" },
                new String[] { "--bridge.runtime.mode=worker" },
                new String[] { "--mode", "unknown" },
                new String[] { "--mode", "local" },
                new String[] { "--mode" },
                new String[] { "--plan", "plan.json", "--plan", "other.json" },
                new String[] { "--unknown", "plan.json" },
                new String[] { "--plan", "invalid\u0000path" })
                .stream().map(args -> Arguments.of((Object) args));
    }

    @ParameterizedTest
    @MethodSource("invalidArguments")
    void rejectsInvalidArgumentsBeforeStartingSpring(String[] args) {
        SpringApplication application = mock(SpringApplication.class);

        assertEquals(1, BridgeRuntimeApplication.run(application, error, args));

        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("Usage:"));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("--plan <path>"));
        verifyNoInteractions(application);
    }

    @Test
    void validatesBothFilesDuplicatesAndEmptyOptionsBeforeStartingSpring() throws Exception {
        Path plan = fixture();
        Path config = directory.resolve("bridge.yml");
        Files.writeString(config, "bridge: {}\n");
        for (String[] args : List.of(
                new String[] {"--plan", plan.toString()},
                new String[] {"--config", config.toString()},
                new String[] {"--plan", plan.toString(), "--config", ""},
                new String[] {"--plan", plan.toString(), "--config", "   "},
                new String[] {"--plan", plan.toString(), "--config", "missing.yml"},
                new String[] {"--plan", plan.toString(), "--config", directory.toString()},
                new String[] {"--plan", directory.toString(), "--config", config.toString()},
                new String[] {"--plan", plan.toString(), "--config", config.toString(), "--config", config.toString()},
                new String[] {"--plan", plan.toString(), "--config", config.toString(), "--unknown", "x"})) {
            SpringApplication application = mock(SpringApplication.class);
            assertEquals(1, BridgeRuntimeApplication.run(application, error, args));
            verifyNoInteractions(application);
        }
    }

    @Test
    void translatesEitherArgumentOrderAndClosesSpringContext() throws Exception {
        Path plan = fixture();
        Path config = directory.resolve("bridge config.yml");
        Files.writeString(config, "bridge: {}\n");
        for (String[] args : List.of(
                new String[] {"--plan", plan.toString(), "--config", config.toString()},
                new String[] {"--config", config.toString(), "--plan", plan.toString()})) {
            SpringApplication application = mock(SpringApplication.class);
            ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
            when(application.run("--bridge.runtime.mode=local", "--bridge.execution.plan=" + plan,
                    "--spring.config.location=optional:classpath:/application.yaml," + config.toUri())).thenReturn(context);
            assertEquals(0, BridgeRuntimeApplication.run(application, error, args));
            verify(context).close();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({MongoProperties.class, DynamoProperties.class})
    static class PropertiesConfiguration {}

    @Test
    void loadsExternalYamlWithSpacesUsingRealSpringAndReportsMissingRequiredProperty() throws Exception {
        Path plan = fixture();
        Path config = directory.resolve("external config.yml");
        Files.writeString(config, "bridge:\n  mongodb:\n    uri: mongodb://localhost:27019\n  dynamodb:\n    region: eu-west-1\n");
        SpringApplication application = new SpringApplication(PropertiesConfiguration.class);
        application.addInitializers(context -> context.addApplicationListener(event -> {
            if (event instanceof org.springframework.context.event.ContextRefreshedEvent) {
                assertEquals("mongodb://localhost:27019", context.getBean(MongoProperties.class).uri());
                assertEquals("eu-west-1", context.getBean(DynamoProperties.class).region());
                assertEquals(null, context.getBean(DynamoProperties.class).endpoint());
            }
        }));
        assertEquals(0, BridgeRuntimeApplication.run(application, error, "--plan", plan.toString(), "--config", config.toString()));
        Files.writeString(config, "bridge:\n  dynamodb:\n    region: eu-west-1\n");
        SpringApplication missingConfiguration = new SpringApplication(PropertiesConfiguration.class);
        missingConfiguration.addInitializers(context -> context.addApplicationListener(event -> {
            if (event instanceof org.springframework.context.event.ContextRefreshedEvent) {
                context.getBean(MongoProperties.class);
            }
        }));
        assertEquals(1, BridgeRuntimeApplication.run(missingConfiguration, error,
                "--plan", plan.toString(), "--config", config.toString()));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("bridge.mongodb"));
    }

    @Test
    void reportsSpringFailureAndReturnsOne() throws Exception {
        Path plan = fixture();
        Path config = directory.resolve("bridge.yml");
        Files.writeString(config, "bridge: {}\n");
        SpringApplication application = mock(SpringApplication.class);
        when(application.run("--bridge.runtime.mode=local", "--bridge.execution.plan=" + plan,
                "--spring.config.location=optional:classpath:/application.yaml," + config.toUri()))
                .thenThrow(new IllegalStateException("Invalid infrastructure configuration"));
        assertEquals(1, BridgeRuntimeApplication.run(application, error, "--plan", plan.toString(), "--config", config.toString()));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("Invalid infrastructure configuration"));
    }

    @Test
    void localCliOverridesWorkerModeFromYamlAndFinishesThePlan() throws Exception {
        Path plan = fixture();
        RecordCursor cursor = mock(RecordCursor.class);
        when(source.open()).thenReturn(cursor);

        assertEquals(0, runPlan(plan, "bridge:\n  runtime:\n    mode: worker\n"));

        verify(source).open();
        verify(cursor).close();
    }

    @Test
    void acceptsPlanPathWithSpacesAndExecutesUsingRealRunner() throws Exception {
        Path plan = fixture();
        RecordCursor cursor = mock(RecordCursor.class);
        when(source.open()).thenReturn(cursor);

        assertEquals(0, runPlan(plan));

        assertEquals("", errors.toString(StandardCharsets.UTF_8));
        verify(source).open();
        verify(cursor).close();
    }

    @Test
    void rejectsMissingFileBeforeOpeningConnectors() throws Exception {
        Path plan = directory.resolve("missing.json");

        assertEquals(1, runPlan(plan));

        assertTrue(errors.toString(StandardCharsets.UTF_8).contains(plan.toString()));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("Plan file does not exist"));
        verifyNoInteractions(source, target);
    }

    @Test
    void rejectsUnreadableJsonBeforeOpeningConnectors() throws Exception {
        Path plan = directory.resolve("broken.json");
        Files.writeString(plan, "{ invalid JSON");

        assertEquals(1, runPlan(plan));

        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("Failed to execute plan"));
        verifyNoInteractions(source, target);
    }

    @Test
    void rejectsInvalidPlanBeforeOpeningConnectors() throws Exception {
        Path plan = fixture();
        Files.writeString(plan, Files.readString(plan).replace("\"planVersion\": 1", "\"planVersion\": 2"));

        assertEquals(1, runPlan(plan));

        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("Unsupported"));
        verifyNoInteractions(source, target);
    }

    private Path fixture() throws Exception {
        Path plan = directory.resolve("valid plan.json");
        try (InputStream input = getClass().getResourceAsStream("/plans/custom-connectors-test.json")) {
            Files.copy(input, plan);
        }
        return plan;
    }

    private int runPlan(Path plan) throws Exception {
        return runPlan(plan, "bridge: {}\n");
    }

    private int runPlan(Path plan, String configuration) throws Exception {
        Path config = directory.resolve("bridge config.yml");
        Files.writeString(config, configuration);
        SpringApplication application = new SpringApplication(ExecutionConfiguration.class, WorkerConfiguration.class);
        application.addInitializers(context -> {
            context.getBeanFactory().registerSingleton("sourceFactory", new SourceTechnologyFactory() {
                public String technology() { return "custom-source"; }
                public SourceConnector createConnector(ConnectorPlan plan) { return source; }
            });
            context.getBeanFactory().registerSingleton("targetFactory", new TargetConnectorFactory() {
                public String technology() { return "custom-target"; }
                public TargetConnector create(ConnectorPlan plan) { return target; }
            });
        });
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        return BridgeRuntimeApplication.run(application, error, "--config", config.toString(), "--plan", plan.toString());
    }

    private Path workerConfig() throws Exception {
        Path config = directory.resolve("worker config.yml");
        Files.writeString(config, "bridge:\n  runtime:\n    mode: local\n"
                + "  mongodb:\n    uri: mongodb://localhost:27019\n"
                + "  dynamodb:\n    region: eu-west-1\n"
                + "  sqs:\n    queue-url: https://sqs.eu-west-1.amazonaws.com/123456789012/work\n");
        return config;
    }

}
