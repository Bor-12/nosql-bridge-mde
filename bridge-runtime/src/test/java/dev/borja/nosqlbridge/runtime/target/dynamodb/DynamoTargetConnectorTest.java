package dev.borja.nosqlbridge.runtime.target.dynamodb;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import dev.borja.nosqlbridge.runtime.model.LogicalRecord;
import dev.borja.nosqlbridge.runtime.model.LogicalValue;
import dev.borja.nosqlbridge.runtime.plan.ConnectorPlan;
import dev.borja.nosqlbridge.runtime.target.TargetConnector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamoTargetConnectorTest {

    @Mock
    private DynamoDbClient dynamoDbClient;

    private final DynamoLogicalValueConverter converter = new DynamoLogicalValueConverter();
    private TargetConnector connector;

    @BeforeEach
    void setUp() {
        connector = new DynamoTargetConnector(dynamoDbClient, converter, plan());
    }

    @Test
    void rejectsNullClient() {
        assertEquals("DynamoDB client must not be null.", assertThrows(IllegalArgumentException.class,
                () -> new DynamoTargetConnector(null, converter, plan())).getMessage());
    }

    @Test
    void rejectsNullConverter() {
        assertEquals("DynamoDB logical value converter must not be null.", assertThrows(IllegalArgumentException.class,
                () -> new DynamoTargetConnector(dynamoDbClient, null, plan())).getMessage());
        verifyNoInteractions(dynamoDbClient);
    }

    @Test
    void rejectsNullPlan() {
        assertEquals("DynamoDB connector plan must not be null.", assertThrows(IllegalArgumentException.class,
                () -> new DynamoTargetConnector(dynamoDbClient, converter, null)).getMessage());
        verifyNoInteractions(dynamoDbClient);
    }

    @Test
    void rejectsWrongOrNullTechnology() {
        for (String technology : new String[] {"mongodb", "DynamoDB", "dynamodb ", "", null}) {
            assertEquals("DynamoDB connector technology must be dynamodb.",
                    assertThrows(IllegalArgumentException.class, () -> new DynamoTargetConnector(
                            dynamoDbClient, converter, new ConnectorPlan(technology, plan().config()))).getMessage());
        }
        verifyNoInteractions(dynamoDbClient);
    }

    @Test
    void rejectsNullConfig() {
        assertInvalidConfig(null, "table");
    }

    @ParameterizedTest(name = "{0}: missing")
    @ValueSource(strings = {"table", "partitionKey"})
    void rejectsMissingRequiredConfig(String name) {
        Map<String, String> config = new HashMap<>(plan().config());
        config.remove(name);
        assertInvalidConfig(config, name);
    }

    @ParameterizedTest(name = "{0}: null")
    @ValueSource(strings = {"table", "partitionKey", "sortKey"})
    void rejectsPresentNullConfigValue(String name) {
        Map<String, String> config = new HashMap<>(plan().config());
        config.put(name, null);
        assertInvalidConfig(config, name);
    }

    @ParameterizedTest(name = "{0}: blank")
    @ValueSource(strings = {"table", "partitionKey", "sortKey"})
    void rejectsBlankConfigValue(String name) {
        Map<String, String> config = new HashMap<>(plan().config());
        config.put(name, " \t");
        assertInvalidConfig(config, name);
    }

    @Test
    void acceptsAbsentSortKeyWithoutCallingClient() {
        new DynamoTargetConnector(dynamoDbClient, converter, plan());
        verifyNoInteractions(dynamoDbClient);
    }

    @Test
    void rejectsSamePartitionAndSortKeyNamesWithoutCallingClient() {
        ConnectorPlan plan = new ConnectorPlan("dynamodb",
                Map.of("table", "Products", "partitionKey", "id", "sortKey", "id"));

        assertEquals("DynamoDB partitionKey and sortKey must be different.",
                assertThrows(IllegalArgumentException.class,
                        () -> new DynamoTargetConnector(dynamoDbClient, converter, plan)).getMessage());
        verifyNoInteractions(dynamoDbClient);
    }

    @Test
    void rejectsNullRecordWithoutCallingClient() {
        assertEquals("Logical record must not be null.",
                assertThrows(IllegalArgumentException.class, () -> connector.write(null)).getMessage());
        verifyNoInteractions(dynamoDbClient);
    }

    @Test
    void rejectsMissingPartitionKeyWithoutCallingClient() {
        assertInvalidRecord(new LogicalRecord(Map.of("name", new LogicalValue.StringValue("Product"))),
                "DynamoDB item is missing key: id");
    }

    @Test
    void rejectsNullPartitionKeyWithoutCallingClient() {
        assertInvalidRecord(new LogicalRecord(Map.of("id", new LogicalValue.NullValue())),
                "DynamoDB key must not be NULL: id");
    }

    @Test
    void rejectsNullConvertedPartitionKeyWithoutCallingClient() {
        DynamoLogicalValueConverter injectedConverter = mock(DynamoLogicalValueConverter.class);
        LogicalRecord record = record();
        Map<String, AttributeValue> attributes = new HashMap<>();
        attributes.put("id", null);
        when(injectedConverter.toAttributeMap(record)).thenReturn(attributes);
        connector = new DynamoTargetConnector(dynamoDbClient, injectedConverter, plan());

        assertInvalidRecord(record, "DynamoDB key must not be NULL: id");

        verify(injectedConverter).toAttributeMap(record);
        verifyNoMoreInteractions(injectedConverter);
    }

    @Test
    void rejectsMissingSortKeyWithoutCallingClient() {
        connector = new DynamoTargetConnector(dynamoDbClient, converter, planWithSortKey());
        assertInvalidRecord(record(), "DynamoDB item is missing key: createdAt");
    }

    @Test
    void rejectsNullSortKeyWithoutCallingClient() {
        connector = new DynamoTargetConnector(dynamoDbClient, converter, planWithSortKey());
        assertInvalidRecord(new LogicalRecord(Map.of("id", new LogicalValue.StringValue("P001"),
                "createdAt", new LogicalValue.NullValue())), "DynamoDB key must not be NULL: createdAt");
    }

    @Test
    void writesOneRecordWithoutSortKey() {
        connector.write(record());

        PutItemRequest request = captureRequest();
        assertEquals("Products", request.tableName());
        assertEquals(Map.of("id", AttributeValue.builder().s("P001").build(),
                "name", AttributeValue.builder().s("Portatil Lenovo").build()), request.item());
    }

    @Test
    void writesOneRecordWithSortKeyAndOtherAttributes() {
        connector = new DynamoTargetConnector(dynamoDbClient, converter, planWithSortKey());
        LogicalRecord record = new LogicalRecord(Map.of(
                "id", new LogicalValue.StringValue("P001"),
                "createdAt", new LogicalValue.StringValue("2026-10-14T10:00:00Z"),
                "name", new LogicalValue.StringValue("Portatil Lenovo"),
                "price", new LogicalValue.DecimalValue(new BigDecimal("899.99")),
                "metadata", new LogicalValue.ObjectValue(Map.of("active", new LogicalValue.BooleanValue(true))),
                "tags", new LogicalValue.ArrayValue(List.of(new LogicalValue.StringValue("laptop"))),
                "description", new LogicalValue.NullValue()));

        connector.write(record);

        PutItemRequest request = captureRequest();
        assertEquals("Products", request.tableName());
        assertEquals(Map.of(
                "id", AttributeValue.builder().s("P001").build(),
                "createdAt", AttributeValue.builder().s("2026-10-14T10:00:00Z").build(),
                "name", AttributeValue.builder().s("Portatil Lenovo").build(),
                "price", AttributeValue.builder().n("899.99").build(),
                "metadata", AttributeValue.builder().m(Map.of("active", AttributeValue.builder().bool(true).build()))
                        .build(),
                "tags", AttributeValue.builder().l(AttributeValue.builder().s("laptop").build()).build(),
                "description", AttributeValue.builder().nul(true).build()), request.item());
    }

    @Test
    void writesEqualPartitionAndSortKeyValuesForDifferentAttributes() {
        connector = new DynamoTargetConnector(dynamoDbClient, converter,
                new ConnectorPlan("dynamodb", Map.of("table", "Products", "partitionKey", "category",
                        "sortKey", "code")));
        LogicalRecord record = new LogicalRecord(Map.of("category", new LogicalValue.StringValue("A"),
                "code", new LogicalValue.StringValue("A")));

        connector.write(record);

        PutItemRequest request = captureRequest();
        assertEquals("Products", request.tableName());
        assertEquals(Map.of("category", AttributeValue.builder().s("A").build(),
                "code", AttributeValue.builder().s("A").build()), request.item());
    }

    @Test
    void delegatesConversionAndValidatesConvertedKeys() {
        DynamoLogicalValueConverter injectedConverter = mock(DynamoLogicalValueConverter.class);
        LogicalRecord record = new LogicalRecord(Map.of());
        Map<String, AttributeValue> attributes = Map.of("id", AttributeValue.builder().s("converted").build(),
                "extra", AttributeValue.builder().n("42").build());
        when(injectedConverter.toAttributeMap(record)).thenReturn(attributes);
        connector = new DynamoTargetConnector(dynamoDbClient, injectedConverter, plan());

        connector.write(record);

        verify(injectedConverter).toAttributeMap(record);
        verifyNoMoreInteractions(injectedConverter);
        assertEquals(attributes, captureRequest().item());
    }

    @Test
    void propagatesConverterFailureWithoutCallingClient() {
        LogicalRecord record = new LogicalRecord(Map.of("id", new LogicalValue.StringValue("P001"),
                "createdAt", new LogicalValue.TimestampValue(Instant.parse("2026-10-14T10:00:00Z"))));
        assertThrows(IllegalArgumentException.class, () -> connector.write(record));
        verifyNoInteractions(dynamoDbClient);
    }

    @Test
    void preservesConfiguredAttributeNames() {
        connector = new DynamoTargetConnector(dynamoDbClient, converter,
                new ConnectorPlan("dynamodb", Map.of("table", "OtherTable", "partitionKey", "product.id",
                        "sortKey", "version")));
        connector.write(new LogicalRecord(Map.of("product.id", new LogicalValue.StringValue("P001"),
                "version", new LogicalValue.IntegerValue(2))));

        PutItemRequest request = captureRequest();
        assertEquals("OtherTable", request.tableName());
        assertEquals(Map.of("product.id", AttributeValue.builder().s("P001").build(),
                "version", AttributeValue.builder().n("2").build()), request.item());
    }

    @Test
    void propagatesSdkExceptionWithoutRetrying() {
        DynamoDbException failure = (DynamoDbException) DynamoDbException.builder().message("Write failed").build();
        when(dynamoDbClient.putItem(any(PutItemRequest.class))).thenThrow(failure);

        assertSame(failure, assertThrows(DynamoDbException.class, () -> connector.write(record())));
        captureRequest();
    }

    private ConnectorPlan plan() {
        return new ConnectorPlan("dynamodb", Map.of("table", "Products", "partitionKey", "id"));
    }

    private ConnectorPlan planWithSortKey() {
        return new ConnectorPlan("dynamodb", Map.of("table", "Products", "partitionKey", "id",
                "sortKey", "createdAt"));
    }

    private LogicalRecord record() {
        return new LogicalRecord(Map.of("id", new LogicalValue.StringValue("P001"),
                "name", new LogicalValue.StringValue("Portatil Lenovo")));
    }

    private void assertInvalidConfig(Map<String, String> config, String name) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new DynamoTargetConnector(dynamoDbClient, converter, new ConnectorPlan("dynamodb", config)));
        assertTrue(exception.getMessage().contains(name));
        verifyNoInteractions(dynamoDbClient);
    }

    private void assertInvalidRecord(LogicalRecord record, String message) {
        assertEquals(message, assertThrows(IllegalArgumentException.class, () -> connector.write(record)).getMessage());
        verifyNoInteractions(dynamoDbClient);
    }

    private PutItemRequest captureRequest() {
        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDbClient).putItem(captor.capture());
        verifyNoMoreInteractions(dynamoDbClient);
        return captor.getValue();
    }
}
