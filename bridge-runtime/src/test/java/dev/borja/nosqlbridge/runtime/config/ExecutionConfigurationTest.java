package dev.borja.nosqlbridge.runtime.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;

import dev.borja.nosqlbridge.runtime.plan.UnsupportedPlanVersionException;
import dev.borja.nosqlbridge.runtime.runner.PlanRunner;
import dev.borja.nosqlbridge.runtime.connector.mongodb.MongoConnectorConfiguration;
import dev.borja.nosqlbridge.runtime.connector.dynamodb.DynamoConnectorConfiguration;
import dev.borja.nosqlbridge.runtime.connector.mongodb.MongoLogicalValueConverter;
import dev.borja.nosqlbridge.runtime.connector.dynamodb.DynamoLogicalValueConverter;
import dev.borja.nosqlbridge.runtime.model.LogicalRecord;
import dev.borja.nosqlbridge.runtime.connector.ConnectorRegistry;
import dev.borja.nosqlbridge.runtime.connector.SourceConnectorFactory;
import dev.borja.nosqlbridge.runtime.connector.TargetConnectorFactory;
import dev.borja.nosqlbridge.runtime.connector.mongodb.MongoSourceConnectorFactory;
import dev.borja.nosqlbridge.runtime.connector.dynamodb.DynamoTargetConnectorFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExecutionConfigurationTest {

    private Path testPlan;

    private final MongoClient mongoClient = mock(MongoClient.class);
    private final DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(MongoClient.class, () -> mongoClient)
            .withBean(DynamoDbClient.class, () -> dynamoDbClient)
            .withUserConfiguration(ExecutionConfiguration.class,
                    MongoConnectorConfiguration.class, DynamoConnectorConfiguration.class);

    @TempDir
    private Path tempDirectory;

    @BeforeEach
    void resolveTestPlanFromClasspath() throws Exception {
        var resource = getClass().getResource("/plans/mongodb-to-dynamodb-test.json");
        assertNotNull(resource, "Execution plan fixture must be available on the test classpath.");
        testPlan = Path.of(resource.toURI());
    }

    @Test
    void doesNotRegisterRunnerOrMigrateWithoutPlanProperty() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(CommandLineRunner.class).isEmpty());
            verifyNoInteractions(mongoClient, dynamoDbClient);
        });
    }

    @Test
    void loadsValidatesAndExecutesFixtureWithInjectedClients() {
        executePlan(contextRunner);
    }

    @Test
    void usesInjectedConvertersWithoutRegisteringDuplicates() {
        int[] conversions = { 0, 0 };
        MongoLogicalValueConverter mongoConverter = new MongoLogicalValueConverter() {
            @Override
            public LogicalRecord toLogicalRecord(Document document) {
                conversions[0]++;
                return super.toLogicalRecord(document);
            }
        };
        DynamoLogicalValueConverter dynamoConverter = new DynamoLogicalValueConverter() {
            @Override
            public Map<String, AttributeValue> toAttributeMap(LogicalRecord record) {
                conversions[1]++;
                return super.toAttributeMap(record);
            }
        };
        executePlan(contextRunner
                .withBean(MongoLogicalValueConverter.class, () -> mongoConverter)
                .withBean(DynamoLogicalValueConverter.class, () -> dynamoConverter));
        assertEquals(1, conversions[0]);
        assertEquals(1, conversions[1]);
    }

    private void executePlan(ApplicationContextRunner runner) {
        MongoDatabase database = mock(MongoDatabase.class);
        @SuppressWarnings("unchecked")
        MongoCollection<Document> collection = mock(MongoCollection.class);
        @SuppressWarnings("unchecked")
        FindIterable<Document> documents = mock(FindIterable.class);
        @SuppressWarnings("unchecked")
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(mongoClient.getDatabase("shop")).thenReturn(database);
        when(database.getCollection("products")).thenReturn(collection);
        when(collection.find()).thenReturn(documents);
        when(documents.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(new Document("_id", 42)
                .append("name", "Product").append("price", "12.50").append("stock", "3")
                .append("category", new Document("name", "Books")).append("ignored", "drop me"));

        runner.withPropertyValues("bridge.execution.plan=" + testPlan).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(SourceConnectorFactory.class).size());
            assertEquals(1, context.getBeansOfType(TargetConnectorFactory.class).size());
            ConnectorRegistry registry = context.getBean(ConnectorRegistry.class);
            assertSame(context.getBean(MongoSourceConnectorFactory.class), registry.sourceFactory("mongodb"));
            assertSame(context.getBean(DynamoTargetConnectorFactory.class), registry.targetFactory("dynamodb"));
            assertEquals(1, context.getBeansOfType(MongoLogicalValueConverter.class).size());
            assertEquals(1, context.getBeansOfType(DynamoLogicalValueConverter.class).size());
            verifyNoInteractions(mongoClient, dynamoDbClient);

            context.getBean(PlanRunner.class).run();

            verify(dynamoDbClient).putItem(PutItemRequest.builder().tableName("Products").item(Map.of(
                    "id", AttributeValue.builder().s("42").build(),
                    "name", AttributeValue.builder().s("Product").build(),
                    "price", AttributeValue.builder().n("12.50").build(),
                    "stock", AttributeValue.builder().n("3").build(),
                    "category", AttributeValue.builder().s("Books").build())).build());
            verify(cursor).close();
        });
    }

    @Test
    void rejectsUnsupportedSourceBeforeAccessingClients() throws Exception {
        Path planPath = modifiedPlan("\"mongodb\"", "\"unsupported\"");

        assertRejectedTechnology(planPath, "source");
    }

    @Test
    void rejectsUnsupportedTargetBeforeAccessingClients() throws Exception {
        Path planPath = modifiedPlan("\"dynamodb\"", "\"unsupported\"");

        assertRejectedTechnology(planPath, "target");
    }

    @Test
    void validatesPlanVersionBeforeAccessingClients() throws Exception {
        Path planPath = modifiedPlan("\"planVersion\": 1", "\"planVersion\": 2");

        contextRunner.withPropertyValues("bridge.execution.plan=" + planPath).run(context -> {
            assertNull(context.getStartupFailure());
            assertThrows(UnsupportedPlanVersionException.class, () -> context.getBean(PlanRunner.class).run());
            verifyNoInteractions(mongoClient, dynamoDbClient);
        });
    }

    @Test
    void propagatesSourceFailure() {
        IllegalStateException failure = new IllegalStateException("Source unavailable");
        when(mongoClient.getDatabase("shop")).thenThrow(failure);

        contextRunner.withPropertyValues("bridge.execution.plan=" + testPlan).run(context -> {
            assertNull(context.getStartupFailure());
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> context.getBean(PlanRunner.class).run()));
            verifyNoInteractions(dynamoDbClient);
        });
    }

    private Path modifiedPlan(String original, String replacement) throws Exception {
        Path planPath = tempDirectory.resolve("plan.json");
        Files.writeString(planPath, Files.readString(testPlan).replace(original, replacement));
        return planPath;
    }

    private void assertRejectedTechnology(Path planPath, String connector) {
        contextRunner.withPropertyValues("bridge.execution.plan=" + planPath).run(context -> {
            assertNull(context.getStartupFailure());
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> context.getBean(PlanRunner.class).run());
            assertTrue(failure.getMessage().contains(connector));
            verifyNoInteractions(mongoClient, dynamoDbClient);
        });
    }
}
