package dev.borja.nosqlbridge.orchestrator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.BsonDocument;
import org.bson.BsonObjectId;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.util.ClassUtils;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import tools.jackson.databind.ObjectMapper;

import dev.borja.nosqlbridge.common.plan.BridgeExecutionPlan;
import dev.borja.nosqlbridge.common.plan.ConnectorPlan;
import dev.borja.nosqlbridge.common.plan.MappingPlan;
import dev.borja.nosqlbridge.common.plan.PlanLoader;
import dev.borja.nosqlbridge.common.queue.WorkQueue;
import dev.borja.nosqlbridge.common.registry.TechnologyRegistry;
import dev.borja.nosqlbridge.common.source.SourceTechnologyFactory;
import dev.borja.nosqlbridge.common.target.TargetConnectorFactory;
import dev.borja.nosqlbridge.common.work.MigrationId;
import dev.borja.nosqlbridge.common.work.WorkUnitId;
import dev.borja.nosqlbridge.common.work.WorkUnitMessage;
import dev.borja.nosqlbridge.common.work.WorkUnitMessageSerializer;
import dev.borja.nosqlbridge.mongodb.MongoIdRange;
import dev.borja.nosqlbridge.mongodb.MongoSourceTechnologyFactory;
import dev.borja.nosqlbridge.sqs.SqsWorkQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BridgeOrchestratorApplicationTest {

    @TempDir
    Path directory;

    private final ObjectMapper mapper = new ObjectMapper();
    private final WorkUnitMessageSerializer serializer = new WorkUnitMessageSerializer(mapper);
    private final ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
    private final PrintStream output = new PrintStream(outputBytes, true, StandardCharsets.UTF_8);
    private final PrintStream error = new PrintStream(errorBytes, true, StandardCharsets.UTF_8);
    private final MongoClient mongoClient = mock(MongoClient.class);
    private final SqsClient sqsClient = mock(SqsClient.class);
    private final AtomicReference<ConfigurableApplicationContext> started = new AtomicReference<>();
    private final BridgeExecutionPlan plan = new BridgeExecutionPlan(1, "products",
            new ConnectorPlan("mongodb", Map.of("database", "shop", "collection", "products")),
            new ConnectorPlan("dynamodb", Map.of("table", "Products", "partitionKey", "id")), "drop", "preserve",
            List.of(new MappingPlan("_id", "id", "toString", null), new MappingPlan("name", "name", null, null)));

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publishesThroughRealAutoConfigurationsInEitherArgumentOrderAndClosesContext(boolean reverse) throws Exception {
        Path planPath = planFile();
        Path config = configFile("2");
        MongoCursor<BsonDocument> cursor = prepareMongo(4);
        prepareSqs();
        SpringApplication application = application();
        String[] args = reverse
                ? new String[] {"--config", config.toString(), "--plan", planPath.toString()}
                : new String[] {"--plan", planPath.toString(), "--config", config.toString()};

        try (MockedStatic<MongoClients> clients = mockStatic(MongoClients.class)) {
            clients.when(() -> MongoClients.create("mongodb://localhost:27017")).thenReturn(mongoClient);

            assertEquals(0, BridgeOrchestratorApplication.run(application, output, error, args));

            clients.verify(() -> MongoClients.create("mongodb://localhost:27017"));
        }

        assertFalse(started.get().isActive());
        assertEquals("", errorBytes.toString(StandardCharsets.UTF_8));
        ArgumentCaptor<SendMessageRequest> requests = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient, times(2)).sendMessage(requests.capture());
        WorkUnitMessage first = serializer.deserialize(requests.getAllValues().get(0).messageBody());
        WorkUnitMessage second = serializer.deserialize(requests.getAllValues().get(1).messageBody());
        assertEquals(plan, first.plan());
        assertEquals(plan, second.plan());
        assertEquals(first.workUnit().migrationId(), second.workUnit().migrationId());
        assertEquals(new WorkUnitId(0), first.workUnit().workUnitId());
        assertEquals(new WorkUnitId(1), second.workUnit().workUnitId());
        assertEquals(MongoIdRange.fromBson(null, new BsonObjectId(id(2))).toSourcePartition(), first.workUnit().sourcePartition());
        assertEquals(MongoIdRange.fromBson(new BsonObjectId(id(2)), new BsonObjectId(id(4))).toSourcePartition(), second.workUnit().sourcePartition());
        assertTrue(outputBytes.toString(StandardCharsets.UTF_8).contains(first.workUnit().migrationId().value().toString()));
        assertTrue(outputBytes.toString(StandardCharsets.UTF_8).contains("2 publish calls completed successfully"));
        assertFalse(requests.getValue().messageBody().contains("mongodb://localhost:27017"));
        verify(cursor).close();
        verify(mongoClient).close();
        verify(sqsClient).close();
        verify(sqsClient, never()).receiveMessage(any(ReceiveMessageRequest.class));
        verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void usesDefaultPartitionSizeAndFinishesWithoutPublishingForEmptySource() throws Exception {
        Path planPath = planFile();
        Path config = configFile(null);
        MongoCursor<BsonDocument> cursor = prepareMongo(0);

        try (MockedStatic<MongoClients> clients = mockStatic(MongoClients.class)) {
            clients.when(() -> MongoClients.create("mongodb://localhost:27017")).thenReturn(mongoClient);
            assertEquals(0, BridgeOrchestratorApplication.run(application(), output, error,
                    "--plan", planPath.toString(), "--config", config.toString()));
        }

        assertTrue(outputBytes.toString(StandardCharsets.UTF_8).contains("0 publish calls completed successfully"));
        assertFalse(started.get().isActive());
        verify(sqsClient, never()).sendMessage(any(SendMessageRequest.class));
        verify(cursor).close();
    }

    @Test
    void returnsFailureAndPartialCountWhenThirdSendFailsWithoutGeneratingRemainingUnits() throws Exception {
        Path planPath = planFile();
        Path config = configFile("1");
        MongoCursor<BsonDocument> cursor = prepareMongo(4);
        prepareSqs();
        AtomicInteger calls = new AtomicInteger();
        when(sqsClient.sendMessage(any(SendMessageRequest.class))).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 3) {
                throw new IllegalStateException("sensitive endpoint and credential details");
            }
            return SendMessageResponse.builder().build();
        });

        try (MockedStatic<MongoClients> clients = mockStatic(MongoClients.class)) {
            clients.when(() -> MongoClients.create("mongodb://localhost:27017")).thenReturn(mongoClient);
            assertEquals(1, BridgeOrchestratorApplication.run(application(), output, error,
                    "--plan", planPath.toString(), "--config", config.toString()));
        }

        assertEquals("", outputBytes.toString(StandardCharsets.UTF_8));
        assertTrue(errorBytes.toString(StandardCharsets.UTF_8).contains("2 publish calls completed successfully"));
        assertTrue(errorBytes.toString(StandardCharsets.UTF_8).contains("may be uncertain"));
        assertFalse(errorBytes.toString(StandardCharsets.UTF_8).contains("sensitive endpoint"));
        verify(cursor, times(3)).next();
        verify(cursor).close();
        assertFalse(started.get().isActive());
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid-json", "invalid-version", "null-mappings"})
    void rejectsInvalidPlansBeforeReadingSourceOrSendingMessages(String invalid) throws Exception {
        Path planPath = planFile();
        Path config = configFile(null);
        String json = Files.readString(planPath);
        Files.writeString(planPath, switch (invalid) {
            case "invalid-json" -> "{ invalid JSON";
            case "invalid-version" -> json.replace("\"planVersion\":1", "\"planVersion\":2");
            case "null-mappings" -> mapper.writeValueAsString(new BridgeExecutionPlan(1, "invalid", plan.source(),
                    plan.target(), "drop", "preserve", null));
            default -> throw new IllegalArgumentException(invalid);
        });

        try (MockedStatic<MongoClients> clients = mockStatic(MongoClients.class)) {
            assertEquals(1, BridgeOrchestratorApplication.run(application(), output, error,
                    "--plan", planPath.toString(), "--config", config.toString()));
            clients.verifyNoInteractions();
        }

        assertEquals("", outputBytes.toString(StandardCharsets.UTF_8));
        assertFalse(errorBytes.toString(StandardCharsets.UTF_8).isBlank());
        assertFalse(started.get().isActive());
        verify(sqsClient, never()).sendMessage(any(SendMessageRequest.class));
        verifyNoInteractions(mongoClient);
    }

    @Test
    void validatesRequiredFilesAndRejectsUnknownDuplicateIncompleteAndModeArguments() throws Exception {
        Path planPath = planFile();
        Path config = configFile(null);
        for (String[] args : List.of(
                new String[] {}, new String[] {"--plan"}, new String[] {"--config"},
                new String[] {"--config", config.toString()}, new String[] {"--plan", planPath.toString()},
                new String[] {"--plan", "", "--config", config.toString()},
                new String[] {"--plan", "   ", "--config", config.toString()},
                new String[] {"--plan", planPath.toString(), "--config", ""},
                new String[] {"--plan", "missing.json", "--config", config.toString()},
                new String[] {"--plan", planPath.toString(), "--config", "missing.yaml"},
                new String[] {"--plan", directory.toString(), "--config", config.toString()},
                new String[] {"--plan", planPath.toString(), "--config", directory.toString()},
                new String[] {"--plan", "invalid\u0000path", "--config", config.toString()},
                new String[] {"--plan", planPath.toString(), "--config", config.toString(), "--plan", planPath.toString()},
                new String[] {"--plan", planPath.toString(), "--config", config.toString(), "--config", config.toString()},
                new String[] {"--plan", planPath.toString(), "--config", config.toString(), "--unknown", "value"},
                new String[] {"--plan", planPath.toString(), "--config", config.toString(), "--mode", "orchestrator"},
                new String[] {"--plan", planPath.toString(), "--config", "--other"})) {
            SpringApplication application = mock(SpringApplication.class);
            assertEquals(1, BridgeOrchestratorApplication.run(application, output, error, args));
            verifyNoInteractions(application);
        }
        assertTrue(errorBytes.toString(StandardCharsets.UTF_8).contains("Usage: java -jar bridge-orchestrator.jar"));
        assertEquals("", outputBytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void reportsLoaderFailureWithoutInvokingPublisherAndClosesContext() throws Exception {
        Path planPath = planFile();
        Path config = configFile(null);
        SpringApplication application = mock(SpringApplication.class);
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        PlanLoader loader = mock(PlanLoader.class);
        MigrationPublisher publisher = mock(MigrationPublisher.class);
        when(application.run(any(String.class))).thenReturn(context);
        when(context.getBean(PlanLoader.class)).thenReturn(loader);
        when(loader.load(planPath)).thenThrow(new IOException("sensitive content"));

        assertEquals(1, BridgeOrchestratorApplication.run(application, output, error,
                "--plan", planPath.toString(), "--config", config.toString()));

        verifyNoInteractions(publisher);
        verify(context, never()).getBean(MigrationPublisher.class);
        verify(context).close();
        assertFalse(errorBytes.toString(StandardCharsets.UTF_8).contains("sensitive content"));
    }

    @Test
    void acceptsRelativePathsAndDoesNotReportSuccessIfContextCloseFails() throws Exception {
        Path root = Path.of("target").toAbsolutePath().normalize();
        Path relativePlan = Path.of("").toAbsolutePath().relativize(root.resolve("cli-test-plan.json"));
        Path relativeConfig = Path.of("").toAbsolutePath().relativize(root.resolve("cli-test-config.yaml"));
        Files.writeString(relativePlan, mapper.writeValueAsString(plan));
        Files.writeString(relativeConfig, "bridge: {}\n");
        try {
            SpringApplication application = mock(SpringApplication.class);
            ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
            PlanLoader loader = mock(PlanLoader.class);
            MigrationPublisher publisher = mock(MigrationPublisher.class);
            when(application.run("--spring.config.location=optional:classpath:/application.yaml,"
                    + relativeConfig.toAbsolutePath().normalize().toUri())).thenReturn(context);
            when(context.getBean(PlanLoader.class)).thenReturn(loader);
            when(context.getBean(MigrationPublisher.class)).thenReturn(publisher);
            when(loader.load(relativePlan.toAbsolutePath().normalize())).thenReturn(plan);
            when(publisher.publish(plan)).thenReturn(new MigrationPublisher.Result(
                    new MigrationId(UUID.fromString("550e8400-e29b-41d4-a716-446655440000")), 2));
            doThrow(new IllegalStateException("close failed")).when(context).close();

            assertEquals(1, BridgeOrchestratorApplication.run(application, output, error,
                    "--plan", relativePlan.toString(), "--config", relativeConfig.toString()));

            verify(application).setWebApplicationType(WebApplicationType.NONE);
            verify(publisher).publish(plan);
            assertEquals("", outputBytes.toString(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(relativePlan);
            Files.deleteIfExists(relativeConfig);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "invalid"})
    void rejectsInvalidPartitionSizeDuringStartup(String value) {
        new ApplicationContextRunner().withUserConfiguration(BridgeOrchestratorApplication.class)
                .withBean(WorkQueue.class, () -> mock(WorkQueue.class))
                .withPropertyValues("bridge.orchestrator.records-per-partition=" + value)
                .run(context -> assertTrue(context.getStartupFailure() != null));
    }

    @Test
    void requiresWorkQueueWithoutRequiringDatabaseConfiguration() {
        new ApplicationContextRunner().withUserConfiguration(BridgeOrchestratorApplication.class).run(context -> {
            assertTrue(context.getStartupFailure() != null);
            assertTrue(context.getStartupFailure().getMessage().contains("migrationPublisher"));
        });
    }

    private Path planFile() throws IOException {
        Path path = directory.resolve("migration plan.json");
        Files.writeString(path, mapper.writeValueAsString(plan));
        return path;
    }

    private Path configFile(String partitionSize) throws IOException {
        Path path = directory.resolve("orchestrator config.yaml");
        String content = "bridge:\n  mongodb:\n    uri: mongodb://localhost:27017\n"
                + "  sqs:\n    queue-url: http://localhost:9324/queue/orchestrator-test\n";
        if (partitionSize != null) {
            content += "  orchestrator:\n    records-per-partition: " + partitionSize + "\n";
        }
        Files.writeString(path, content);
        return path;
    }

    private SpringApplication application() {
        SpringApplication application = new SpringApplication(BridgeOrchestratorApplication.class);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.addInitializers(context -> {
            started.set(context);
            ((GenericApplicationContext) context).registerBean("sqsClient", SqsClient.class, () -> sqsClient,
                    definition -> definition.setDestroyMethodName("close"));
            context.addApplicationListener(event -> {
                if (event instanceof ContextRefreshedEvent) {
                    assertEquals("bridge-orchestrator", context.getEnvironment().getProperty("spring.application.name"));
                    assertNull(context.getEnvironment().getProperty("bridge.dynamodb.region"));
                    assertEquals(1, context.getBeansOfType(SourceTechnologyFactory.class).size());
                    assertEquals(1, context.getBeansOfType(MigrationPublisher.class).size());
                    TechnologyRegistry registry = context.getBean(TechnologyRegistry.class);
                    assertSame(context.getBean(MongoSourceTechnologyFactory.class), registry.sourceFactory("mongodb"));
                    assertTrue(context.getBeansOfType(TargetConnectorFactory.class).isEmpty());
                    assertInstanceOf(SqsWorkQueue.class, context.getBean(WorkQueue.class));
                    assertTrue(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty());
                    assertFalse(ClassUtils.isPresent("dev.borja.nosqlbridge.worker.WorkerConsumer", getClass().getClassLoader()));
                    assertFalse(ClassUtils.isPresent("software.amazon.awssdk.services.dynamodb.DynamoDbClient", getClass().getClassLoader()));
                    verifyNoInteractions(mongoClient, sqsClient);
                }
            });
        });
        return application;
    }

    @SuppressWarnings("unchecked")
    private MongoCursor<BsonDocument> prepareMongo(int count) {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        FindIterable<BsonDocument> documents = mock(FindIterable.class);
        MongoCursor<BsonDocument> cursor = mock(MongoCursor.class);
        List<BsonDocument> data = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            data.add(new BsonDocument("_id", new BsonObjectId(id(index))));
        }
        Iterator<BsonDocument> remaining = data.iterator();
        when(mongoClient.getDatabase("shop")).thenReturn(database);
        when(database.getCollection("products")).thenReturn(collection);
        when(collection.find(BsonDocument.class)).thenReturn(documents);
        when(documents.projection(new Document("_id", 1))).thenReturn(documents);
        when(documents.sort(new Document("_id", 1))).thenReturn(documents);
        when(documents.hintString("_id_")).thenReturn(documents);
        when(documents.collation(com.mongodb.client.model.Collation.builder().locale("simple").build())).thenReturn(documents);
        when(documents.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenAnswer(invocation -> remaining.hasNext());
        when(cursor.next()).thenAnswer(invocation -> remaining.next());
        return cursor;
    }

    private ObjectId id(int index) {
        return new ObjectId(String.format("%024x", index));
    }

    private void prepareSqs() {
        when(sqsClient.getQueueAttributes(any(GetQueueAttributesRequest.class))).thenReturn(
                GetQueueAttributesResponse.builder().attributes(Map.of(QueueAttributeName.MAXIMUM_MESSAGE_SIZE, "1048576")).build());
        when(sqsClient.sendMessage(any(SendMessageRequest.class))).thenReturn(SendMessageResponse.builder().build());
    }
}
