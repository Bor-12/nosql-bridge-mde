package dev.borja.nosqlbridge.worker.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Collation;
import org.bson.BsonInt32;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;

import dev.borja.nosqlbridge.common.model.LogicalRecord;
import dev.borja.nosqlbridge.common.plan.BridgeExecutionPlan;
import dev.borja.nosqlbridge.common.plan.PlanLoader;
import dev.borja.nosqlbridge.common.queue.WorkQueue;
import dev.borja.nosqlbridge.common.registry.TechnologyRegistry;
import dev.borja.nosqlbridge.common.work.MigrationId;
import dev.borja.nosqlbridge.common.work.WorkUnit;
import dev.borja.nosqlbridge.common.work.WorkUnitId;
import dev.borja.nosqlbridge.common.work.WorkUnitMessage;
import dev.borja.nosqlbridge.common.work.WorkUnitMessageSerializer;
import dev.borja.nosqlbridge.dynamodb.DynamoConnectorConfiguration;
import dev.borja.nosqlbridge.dynamodb.DynamoLogicalValueConverter;
import dev.borja.nosqlbridge.mongodb.MongoConnectorConfiguration;
import dev.borja.nosqlbridge.mongodb.MongoIdRange;
import dev.borja.nosqlbridge.mongodb.MongoLogicalValueConverter;
import dev.borja.nosqlbridge.worker.execution.WorkUnitExecutor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkerWiringTest {
    private final MongoClient mongoClient = mock(MongoClient.class);
    private final DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(WorkerConfiguration.class, MongoConnectorConfiguration.class,
                    DynamoConnectorConfiguration.class)
            .withPropertyValues("bridge.mongodb.uri=mongodb://localhost:27017", "bridge.dynamodb.region=us-east-1")
            .withBean(WorkQueue.class, () -> mock(WorkQueue.class))
            .withBean(MongoClient.class, () -> mongoClient)
            .withBean(DynamoDbClient.class, () -> dynamoDbClient);

    @Test
    void reusesConfiguredMapperForWorkUnitMessagesWithoutCreatingPlanRunners() {
        ObjectMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        contextRunner.withBean(ObjectMapper.class, () -> mapper).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(ObjectMapper.class).size());
            assertSame(mapper, context.getBean(ObjectMapper.class));
            assertEquals(1, context.getBeansOfType(TechnologyRegistry.class).size());
            assertTrue(context.getBeansOfType(org.springframework.boot.CommandLineRunner.class).isEmpty());
            assertTrue(context.getBeansOfType(PlanLoader.class).isEmpty());
            WorkUnitMessage message = new WorkUnitMessage(unit(), plan(mapper));
            WorkUnitMessageSerializer serializer = context.getBean(WorkUnitMessageSerializer.class);
            String json = serializer.serialize(message);
            assertFalse(json.contains("\n"));
            assertTrue(mapper.writeValueAsString(message).contains("\n"));
            assertEquals(message, serializer.deserialize(json));
            verifyNoInteractions(mongoClient, dynamoDbClient);
        });
    }

    @Test
    void executesAWorkUnitUsingConfiguredConnectorsAndMappingBeans() {
        executeWorkUnit(contextRunner);
    }

    @Test
    void reusesInjectedConvertersWithoutRegisteringDuplicates() {
        int[] conversions = {0, 0};
        MongoLogicalValueConverter mongoConverter = new MongoLogicalValueConverter() {
            @Override public LogicalRecord toLogicalRecord(Document document) {
                conversions[0]++;
                return super.toLogicalRecord(document);
            }
        };
        DynamoLogicalValueConverter dynamoConverter = new DynamoLogicalValueConverter() {
            @Override public Map<String, AttributeValue> toAttributeMap(LogicalRecord record) {
                conversions[1]++;
                return super.toAttributeMap(record);
            }
        };
        executeWorkUnit(contextRunner.withBean(MongoLogicalValueConverter.class, () -> mongoConverter)
                .withBean(DynamoLogicalValueConverter.class, () -> dynamoConverter));
        assertEquals(1, conversions[0]);
        assertEquals(1, conversions[1]);
    }

    private void executeWorkUnit(ApplicationContextRunner runner) {
        MongoDatabase database = mock(MongoDatabase.class);
        @SuppressWarnings("unchecked") MongoCollection<Document> collection = mock(MongoCollection.class);
        @SuppressWarnings("unchecked") FindIterable<Document> documents = mock(FindIterable.class);
        @SuppressWarnings("unchecked") MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(mongoClient.getDatabase("shop")).thenReturn(database);
        when(database.getCollection("products")).thenReturn(collection);
        when(collection.find(any(Document.class))).thenReturn(documents);
        when(documents.hintString("_id_")).thenReturn(documents);
        when(documents.collation(any(Collation.class))).thenReturn(documents);
        when(documents.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(new Document("_id", 42).append("name", "Product")
                .append("price", "12.50").append("stock", "3")
                .append("category", new Document("name", "Books")).append("ignored", "drop me"));
        when(dynamoDbClient.batchWriteItem(any(BatchWriteItemRequest.class)))
                .thenReturn(BatchWriteItemResponse.builder().build());
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(MongoLogicalValueConverter.class).size());
            assertEquals(1, context.getBeansOfType(DynamoLogicalValueConverter.class).size());
            BridgeExecutionPlan plan = plan(context.getBean(ObjectMapper.class));
            verifyNoInteractions(mongoClient, dynamoDbClient);
            var result = context.getBean(WorkUnitExecutor.class).execute(unit(), plan);
            assertEquals(1, result.recordsRead());
            assertEquals(1, result.recordsWritten());
            var request = org.mockito.ArgumentCaptor.forClass(BatchWriteItemRequest.class);
            verify(dynamoDbClient).batchWriteItem(request.capture());
            var writes = request.getValue().requestItems().get("Products");
            assertEquals(1, writes.size());
            assertEquals(Map.of("id", AttributeValue.builder().s("42").build(),
                    "name", AttributeValue.builder().s("Product").build(),
                    "price", AttributeValue.builder().n("12.50").build(),
                    "stock", AttributeValue.builder().n("3").build(),
                    "category", AttributeValue.builder().s("Books").build()), writes.getFirst().putRequest().item());
            verify(cursor).close();
        });
    }

    private static BridgeExecutionPlan plan(ObjectMapper mapper) throws Exception {
        return new PlanLoader(mapper).load(Path.of(WorkerWiringTest.class
                .getResource("/plans/mongodb-to-dynamodb-test.json").toURI()));
    }

    private static WorkUnit unit() {
        return new WorkUnit(new MigrationId(UUID.fromString("550e8400-e29b-41d4-a716-446655440000")),
                new WorkUnitId(0), MongoIdRange.fromBson(null, new BsonInt32(42)).toSourcePartition());
    }
}
