package dev.borja.nosqlbridge.worker;

import dev.borja.nosqlbridge.worker.consumer.WorkerPollingTask;

import dev.borja.nosqlbridge.worker.consumer.WorkerConsumer;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.mongodb.client.MongoClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import dev.borja.nosqlbridge.common.queue.WorkQueue;
import dev.borja.nosqlbridge.common.registry.TechnologyRegistry;
import dev.borja.nosqlbridge.common.plan.PlanLoader;
import dev.borja.nosqlbridge.common.plan.PlanValidator;
import dev.borja.nosqlbridge.mongodb.MongoProperties;
import dev.borja.nosqlbridge.mongodb.MongoSourceTechnologyFactory;
import dev.borja.nosqlbridge.dynamodb.DynamoProperties;
import dev.borja.nosqlbridge.dynamodb.DynamoTargetConnectorFactory;
import dev.borja.nosqlbridge.sqs.SqsWorkQueue;
import dev.borja.nosqlbridge.worker.execution.WorkUnitExecutor;
import dev.borja.nosqlbridge.worker.mapping.MappingEngine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BridgeWorkerApplicationTest {
    @TempDir Path directory;
    private final ByteArrayOutputStream errors = new ByteArrayOutputStream();
    private final PrintStream error = new PrintStream(errors, true, StandardCharsets.UTF_8);

    @Test
    void startsOnlyDistributedWorkerEvenWhenYamlContainsObsoleteLocalProperties() throws Exception {
        Path config = workerConfig();
        SpringApplication application = new SpringApplication(BridgeWorkerApplication.class);
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
            assertEquals(0, BridgeWorkerApplication.run(application, error, "--config", config.toString()));
            ConfigurableApplicationContext context = started.get();
            assertTrue(context.isActive());
            assertEquals(1, context.getBeansOfType(WorkerConsumer.class).size());
            assertEquals(1, context.getBeansOfType(WorkerPollingTask.class).size());
            assertEquals(1, context.getBeansOfType(MappingEngine.class).size());
            assertEquals(1, context.getBeansOfType(WorkUnitExecutor.class).size());
            assertInstanceOf(SqsWorkQueue.class, context.getBean(WorkQueue.class));
            TechnologyRegistry registry = context.getBean(TechnologyRegistry.class);
            assertSame(context.getBean(MongoSourceTechnologyFactory.class), registry.sourceFactory("mongodb"));
            assertSame(context.getBean(DynamoTargetConnectorFactory.class), registry.targetFactory("dynamodb"));
            assertTrue(context.getBeansOfType(CommandLineRunner.class).isEmpty());
            assertTrue(context.getBeansOfType(PlanLoader.class).isEmpty());
            assertTrue(context.getBeansOfType(PlanValidator.class).isEmpty());
            assertEquals("bridge-worker", context.getEnvironment().getProperty("spring.application.name"));
            assertEquals("mongodb://localhost:27019", context.getBean(MongoProperties.class).uri());
            assertEquals("eu-west-1", context.getBean(DynamoProperties.class).region());
            verifyNoInteractions(mongoClient, dynamoDbClient);
        } finally {
            if (started.get() != null) started.get().close();
        }
    }

    @Test
    void loadsConfigAndKeepsTheSpringContextOpen() throws Exception {
        Path config = workerConfig();
        SpringApplication application = mock(SpringApplication.class);
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        when(application.run("--spring.config.location=optional:classpath:/application.yaml," + config.toUri()))
                .thenReturn(context);
        assertEquals(0, BridgeWorkerApplication.run(application, error, "--config", config.toString()));
        verify(application).run("--spring.config.location=optional:classpath:/application.yaml," + config.toUri());
        verify(context, never()).close();
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
    }

    @Test
    void reportsWorkerStartupFailureWithoutExposingExceptionContents() throws Exception {
        Path config = workerConfig();
        SpringApplication application = mock(SpringApplication.class);
        when(application.run("--spring.config.location=optional:classpath:/application.yaml," + config.toUri()))
                .thenThrow(new IllegalStateException("private-connection-details"));
        assertEquals(1, BridgeWorkerApplication.run(application, error, "--config", config.toString()));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("Failed to start worker"));
        assertFalse(errors.toString(StandardCharsets.UTF_8).contains("private-connection-details"));
    }

    @Test
    void reportsMissingQueueInfrastructure() throws Exception {
        Path config = workerConfig();
        SpringApplication application = mock(SpringApplication.class);
        when(application.run("--spring.config.location=optional:classpath:/application.yaml," + config.toUri()))
                .thenThrow(new IllegalStateException("Worker mode requires bridge.sqs.queue-url or a WorkQueue bean."));
        assertEquals(1, BridgeWorkerApplication.run(application, error, "--config", config.toString()));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("bridge.sqs.queue-url"));
    }

    @Test
    void rejectsInvalidArgumentsBeforeStartingSpring() throws Exception {
        Path config = workerConfig();
        for (String[] args : List.of(
                new String[] {}, new String[] {"--config"},
                new String[] {"--config", ""}, new String[] {"--config", "   "},
                new String[] {"--config", "--other"},
                new String[] {"--config", "missing.yml"},
                new String[] {"--config", directory.toString()},
                new String[] {"--config", "invalid\u0000path"},
                new String[] {"--config", config.toString(), "--config", config.toString()},
                new String[] {"--config", config.toString(), "--unknown", "value"},
                new String[] {"--mode", "local"}, new String[] {"--mode", "worker"},
                new String[] {"--bridge.runtime.mode=worker"},
                new String[] {"--bridge.execution.plan=plan.json"})) {
            SpringApplication application = mock(SpringApplication.class);
            assertEquals(1, BridgeWorkerApplication.run(application, error, args));
            verifyNoInteractions(application);
        }
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("Usage: java -jar bridge-worker.jar --config <path>"));
        assertFalse(errors.toString(StandardCharsets.UTF_8).contains("--plan <path>"));
    }

    @Test
    void rejectsRemovedPlanOptionEvenWhenBothFilesExist() throws Exception {
        Path config = workerConfig();
        Path plan = directory.resolve("plan.json");
        Files.writeString(plan, "{}");
        for (String[] args : List.of(
                new String[] {"--plan", plan.toString(), "--config", config.toString()},
                new String[] {"--config", config.toString(), "--plan", plan.toString()})) {
            SpringApplication application = mock(SpringApplication.class);
            assertEquals(1, BridgeWorkerApplication.run(application, error, args));
            verifyNoInteractions(application);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({MongoProperties.class, DynamoProperties.class})
    static class PropertiesConfiguration {}

    @Test
    void loadsExternalYamlWithSpacesAndReportsMissingRequiredProperty() throws Exception {
        Path config = directory.resolve("external config.yml");
        Files.writeString(config, "bridge:\n  mongodb:\n    uri: mongodb://localhost:27019\n  dynamodb:\n    region: eu-west-1\n");
        for (boolean missing : List.of(false, true)) {
            if (missing) Files.writeString(config, "bridge:\n  dynamodb:\n    region: eu-west-1\n");
            SpringApplication application = new SpringApplication(PropertiesConfiguration.class);
            AtomicReference<ConfigurableApplicationContext> started = new AtomicReference<>();
            application.addInitializers(context -> {
                started.set(context);
                context.addApplicationListener(event -> {
                    if (event instanceof org.springframework.context.event.ContextRefreshedEvent) {
                        assertEquals("mongodb://localhost:27019", context.getBean(MongoProperties.class).uri());
                        assertEquals("eu-west-1", context.getBean(DynamoProperties.class).region());
                    }
                });
            });
            try {
                assertEquals(missing ? 1 : 0, BridgeWorkerApplication.run(application, error, "--config", config.toString()));
            } finally {
                if (started.get() != null && started.get().isActive()) started.get().close();
            }
        }
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("bridge.mongodb"));
    }

    private Path workerConfig() throws Exception {
        Path config = directory.resolve("worker config.yml");
        Files.writeString(config, "bridge:\n  runtime:\n    mode: local\n"
                + "  execution:\n    plan: ignored-plan.json\n"
                + "  mongodb:\n    uri: mongodb://localhost:27019\n"
                + "  dynamodb:\n    region: eu-west-1\n"
                + "  sqs:\n    queue-url: https://sqs.eu-west-1.amazonaws.com/123456789012/work\n");
        return config;
    }
}
