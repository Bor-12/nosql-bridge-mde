package dev.borja.nosqlbridge.dynamodb;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import dev.borja.nosqlbridge.common.model.LogicalRecord;
import dev.borja.nosqlbridge.common.model.LogicalValue;
import dev.borja.nosqlbridge.common.plan.ConnectorPlan;
import dev.borja.nosqlbridge.common.target.TargetConnector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
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

    @ParameterizedTest
    @ValueSource(ints = {1, 25, 63})
    void splitsBatchIntoNativeRequestsOfAtMost25(int count) {
        stubBatchSuccess();
        List<LogicalRecord> records = records(count);

        connector.writeBatch(records);

        List<BatchWriteItemRequest> requests = captureBatches((count + 24) / 25);
        List<Map<String, AttributeValue>> written = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            List<WriteRequest> writes = requests.get(i).requestItems().get("Products");
            assertEquals(Math.min(25, count - i * 25), writes.size());
            for (WriteRequest write : writes) written.add(write.putRequest().item());
        }
        assertEquals(records.stream().map(converter::toAttributeMap).toList(), written);
        verify(dynamoDbClient, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void emptyBatchDoesNotCallDynamoDb() {
        connector.writeBatch(List.of());
        verifyNoInteractions(dynamoDbClient);
    }

    @Test
    void rejectsNullBatchRecordAndSettings() {
        DynamoLogicalValueConverter injectedConverter = mock(DynamoLogicalValueConverter.class);
        connector = new DynamoTargetConnector(dynamoDbClient, injectedConverter, plan());
        assertThrows(IllegalArgumentException.class, () -> connector.writeBatch(null));
        assertEquals("Logical record must not be null.", assertThrows(IllegalArgumentException.class,
                () -> connector.writeBatch(Arrays.asList((LogicalRecord) null))).getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> new DynamoTargetConnector(dynamoDbClient, converter, plan(), null));
        verifyNoInteractions(injectedConverter, dynamoDbClient);
    }

    @Test
    void nativeBatchPreservesConvertedTypesAndCompositeKey() {
        stubBatchSuccess();
        connector = new DynamoTargetConnector(dynamoDbClient, converter, planWithSortKey());
        LogicalRecord record = new LogicalRecord(Map.of(
                "id", new LogicalValue.StringValue("A"),
                "createdAt", new LogicalValue.IntegerValue(1),
                "amount", new LogicalValue.DecimalValue(new BigDecimal("12.50")),
                "bytes", new LogicalValue.BinaryValue(new byte[] {1, 2}),
                "metadata", new LogicalValue.ObjectValue(Map.of("enabled", new LogicalValue.BooleanValue(true))),
                "tags", new LogicalValue.ArrayValue(List.of(new LogicalValue.NullValue()))));

        connector.writeBatch(List.of(record));

        Map<String, AttributeValue> attributes = captureBatches(1).getFirst()
                .requestItems().get("Products").getFirst().putRequest().item();
        assertEquals("12.50", attributes.get("amount").n());
        assertEquals(2, attributes.get("bytes").b().asByteArray().length);
        assertTrue(attributes.get("metadata").m().get("enabled").bool());
        assertTrue(attributes.get("tags").l().getFirst().nul());
        assertEquals("1", attributes.get("createdAt").n());
    }

    @Test
    void retriesOnlyUnprocessedItemsThenSucceeds() throws Exception {
        DynamoTargetConnector retrying = retryingConnector(2);
        when(dynamoDbClient.batchWriteItem(any(BatchWriteItemRequest.class))).thenAnswer(invocation -> {
            BatchWriteItemRequest request = invocation.getArgument(0);
            List<WriteRequest> writes = request.requestItems().get("Products");
            return writes.size() == 2 ? BatchWriteItemResponse.builder()
                    .unprocessedItems(Map.of("Products", List.of(writes.get(1)))).build()
                    : BatchWriteItemResponse.builder().build();
        });

        retrying.writeBatch(records(2));

        List<BatchWriteItemRequest> requests = captureBatches(2);
        assertEquals(List.of(requests.get(0).requestItems().get("Products").get(1)),
                requests.get(1).requestItems().get("Products"));
        verify(retrying).sleep(any(Duration.class));
    }

    @Test
    void exhaustedRetriesReportPendingCountAndUseCappedExponentialJitter() throws Exception {
        DynamoTargetConnector retrying = retryingConnector(4);
        when(dynamoDbClient.batchWriteItem(any(BatchWriteItemRequest.class))).thenAnswer(invocation ->
                BatchWriteItemResponse.builder().unprocessedItems(
                        ((BatchWriteItemRequest) invocation.getArgument(0)).requestItems()).build());

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> retrying.writeBatch(records(2)));

        assertEquals("DynamoDB batch write exhausted 4 retries; 2 unprocessed items remain for table Products.",
                failure.getMessage());
        captureBatches(5);
        ArgumentCaptor<Duration> delays = ArgumentCaptor.forClass(Duration.class);
        verify(retrying, times(4)).sleep(delays.capture());
        long[] caps = {10, 20, 30, 30};
        for (int i = 0; i < caps.length; i++) {
            assertFalse(delays.getAllValues().get(i).isNegative());
            assertTrue(delays.getAllValues().get(i).toMillis() < caps[i]);
        }
    }

    @Test
    void zeroRetriesMakesOnlyInitialRequest() {
        connector = new DynamoTargetConnector(dynamoDbClient, converter, plan(),
                new DynamoBatchWriteProperties(0, Duration.ofMillis(1), Duration.ofMillis(1)));
        when(dynamoDbClient.batchWriteItem(any(BatchWriteItemRequest.class))).thenAnswer(invocation ->
                BatchWriteItemResponse.builder().unprocessedItems(
                        ((BatchWriteItemRequest) invocation.getArgument(0)).requestItems()).build());

        assertThrows(IllegalStateException.class, () -> connector.writeBatch(records(1)));
        captureBatches(1);
    }

    @Test
    void interruptionDuringBackoffStopsRetriesAndPreservesInterruptFlag() throws Exception {
        DynamoTargetConnector retrying = spy(new DynamoTargetConnector(dynamoDbClient, converter, plan()));
        doThrow(new InterruptedException("cancelled")).when(retrying).sleep(any(Duration.class));
        when(dynamoDbClient.batchWriteItem(any(BatchWriteItemRequest.class))).thenAnswer(invocation ->
                BatchWriteItemResponse.builder().unprocessedItems(
                        ((BatchWriteItemRequest) invocation.getArgument(0)).requestItems()).build());

        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> retrying.writeBatch(records(1)));
            assertTrue(failure.getCause() instanceof InterruptedException);
            assertTrue(Thread.currentThread().isInterrupted());
            captureBatches(1);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void alreadyInterruptedThreadDoesNotCallDynamoDb() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalStateException.class, () -> connector.writeBatch(records(1)));
            assertTrue(Thread.currentThread().isInterrupted());
            verifyNoInteractions(dynamoDbClient);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void sdkServiceAndClientFailuresAreNotRetriedByConnector() {
        RuntimeException[] failures = {
                ResourceNotFoundException.builder().message("missing table").build(),
                SdkClientException.create("connection failed")};
        when(dynamoDbClient.batchWriteItem(any(BatchWriteItemRequest.class))).thenThrow(failures[0], failures[1]);

        for (RuntimeException failure : failures) {
            assertSame(failure, assertThrows(RuntimeException.class, () -> connector.writeBatch(records(1))));
        }
        captureBatches(2);
    }

    @Test
    void repeatedKeysAreWrittenInSeparateCompletedRequestsInInputOrder() throws Exception {
        DynamoTargetConnector retrying = retryingConnector(1);
        LogicalRecord first = new LogicalRecord(Map.of("id", new LogicalValue.StringValue("A"),
                "version", new LogicalValue.IntegerValue(1)));
        LogicalRecord last = new LogicalRecord(Map.of("id", new LogicalValue.StringValue("A"),
                "version", new LogicalValue.IntegerValue(2)));
        when(dynamoDbClient.batchWriteItem(any(BatchWriteItemRequest.class))).thenAnswer(new org.mockito.stubbing.Answer<BatchWriteItemResponse>() {
            private int calls;
            public BatchWriteItemResponse answer(org.mockito.invocation.InvocationOnMock invocation) {
                BatchWriteItemRequest request = invocation.getArgument(0);
                return calls++ == 0 ? BatchWriteItemResponse.builder().unprocessedItems(request.requestItems()).build()
                        : BatchWriteItemResponse.builder().build();
            }
        });

        retrying.writeBatch(List.of(first, last));

        List<BatchWriteItemRequest> requests = captureBatches(3);
        assertEquals(requests.get(0), requests.get(1));
        assertEquals("1", requests.get(1).requestItems().get("Products").getFirst().putRequest().item().get("version").n());
        assertEquals("2", requests.get(2).requestItems().get("Products").getFirst().putRequest().item().get("version").n());
    }

    @Test
    void duplicateNumericKeysUseNumericEquality() {
        stubBatchSuccess();
        connector.writeBatch(List.of(
                new LogicalRecord(Map.of("id", new LogicalValue.IntegerValue(1))),
                new LogicalRecord(Map.of("id", new LogicalValue.DecimalValue(new BigDecimal("1.00"))))));
        assertEquals(2, captureBatches(2).size());
    }

    @Test
    void duplicateBinaryKeysUseContentEquality() {
        stubBatchSuccess();
        connector.writeBatch(List.of(
                new LogicalRecord(Map.of("id", new LogicalValue.BinaryValue(new byte[] {1, 2}))),
                new LogicalRecord(Map.of("id", new LogicalValue.BinaryValue(new byte[] {1, 2})))));

        captureBatches(2);
    }

    @Test
    void compositeKeyDuplicatesCompareBothComponentsNumerically() {
        stubBatchSuccess();
        connector = new DynamoTargetConnector(dynamoDbClient, converter, planWithSortKey());
        connector.writeBatch(List.of(
                new LogicalRecord(Map.of("id", new LogicalValue.StringValue("A"), "createdAt", new LogicalValue.IntegerValue(1))),
                new LogicalRecord(Map.of("id", new LogicalValue.StringValue("A"), "createdAt",
                        new LogicalValue.DecimalValue(new BigDecimal("1.00"))))));

        captureBatches(2);
    }

    @Test
    void failedSubBatchStopsBeforeSubmittingLaterSubBatches() {
        connector = new DynamoTargetConnector(dynamoDbClient, converter, plan(),
                new DynamoBatchWriteProperties(0, null, null));
        when(dynamoDbClient.batchWriteItem(any(BatchWriteItemRequest.class))).thenAnswer(invocation ->
                BatchWriteItemResponse.builder().unprocessedItems(
                        ((BatchWriteItemRequest) invocation.getArgument(0)).requestItems()).build());

        assertThrows(IllegalStateException.class, () -> connector.writeBatch(records(63)));
        assertEquals(25, captureBatches(1).getFirst().requestItems().get("Products").size());
    }

    @Test
    void validatesBatchSortKeyPresenceAndSize() {
        connector = new DynamoTargetConnector(dynamoDbClient, converter, planWithSortKey());
        assertThrows(IllegalArgumentException.class, () -> connector.writeBatch(List.of(record())));
        assertThrows(IllegalArgumentException.class, () -> connector.writeBatch(List.of(new LogicalRecord(Map.of(
                "id", new LogicalValue.StringValue("A"), "createdAt", new LogicalValue.StringValue("x".repeat(1025)))))));
        verifyNoInteractions(dynamoDbClient);
    }

    @Test
    void compositeKeysAllowSamePartitionAndDifferentSortKeys() {
        stubBatchSuccess();
        connector = new DynamoTargetConnector(dynamoDbClient, converter, planWithSortKey());
        connector.writeBatch(List.of(
                new LogicalRecord(Map.of("id", new LogicalValue.StringValue("A"), "createdAt", new LogicalValue.IntegerValue(1))),
                new LogicalRecord(Map.of("id", new LogicalValue.StringValue("A"), "createdAt", new LogicalValue.IntegerValue(2)))));
        assertEquals(2, captureBatches(1).getFirst().requestItems().get("Products").size());
    }

    @Test
    void oversizedItemIsRejectedBeforeCallingAws() {
        LogicalRecord oversized = new LogicalRecord(Map.of("id", new LogicalValue.StringValue("A"),
                "payload", new LogicalValue.StringValue("x".repeat(400 * 1024))));
        assertThrows(IllegalArgumentException.class, () -> connector.writeBatch(List.of(oversized)));
        verifyNoInteractions(dynamoDbClient);
    }

    @Test
    void acceptsStringItemAtStorageSizeLimit() {
        stubBatchSuccess();
        LogicalRecord atLimit = new LogicalRecord(Map.of("id", new LogicalValue.StringValue("A"),
                "payload", new LogicalValue.StringValue("x".repeat(400 * 1024 - 10))));
        connector.writeBatch(List.of(atLimit));
        captureBatches(1);
    }

    @Test
    void escapedJsonSizeSplitsBefore16MiBDespiteFewerThan25Items() {
        stubBatchSuccess();
        List<LogicalRecord> records = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            records.add(new LogicalRecord(Map.of("id", new LogicalValue.IntegerValue(i),
                    "payload", new LogicalValue.StringValue("\u0000".repeat(350_000)))));
        }

        connector.writeBatch(records);

        List<BatchWriteItemRequest> requests = captureBatches(2);
        assertEquals(7, requests.get(0).requestItems().get("Products").size());
        assertEquals(3, requests.get(1).requestItems().get("Products").size());
    }

    @Test
    void binaryPayloadsFitOneRequestWithoutDoubleCountingBase64() {
        stubBatchSuccess();
        LogicalValue.BinaryValue payload = new LogicalValue.BinaryValue(new byte[400 * 1024 - 32]);
        List<LogicalRecord> records = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            records.add(new LogicalRecord(Map.of("id", new LogicalValue.StringValue("P" + i), "payload", payload)));
        }

        connector.writeBatch(records);

        List<BatchWriteItemRequest> requests = captureBatches(1);
        assertEquals(25, requests.get(0).requestItems().get("Products").size());
    }

    @Test
    void invalidKeysAndUnsupportedLogicalValuesFailBeforeAws() {
        for (LogicalRecord record : List.of(
                new LogicalRecord(Map.of()),
                new LogicalRecord(Map.of("id", new LogicalValue.NullValue())),
                new LogicalRecord(Map.of("id", new LogicalValue.StringValue(""))),
                new LogicalRecord(Map.of("id", new LogicalValue.StringValue("é".repeat(1025)))),
                new LogicalRecord(Map.of("id", new LogicalValue.BooleanValue(true))),
                new LogicalRecord(Map.of("id", new LogicalValue.BinaryValue(new byte[0]))),
                new LogicalRecord(Map.of("id", new LogicalValue.BinaryValue(new byte[2049]))),
                new LogicalRecord(Map.of("id", new LogicalValue.TimestampValue(Instant.EPOCH))))) {
            assertThrows(IllegalArgumentException.class, () -> connector.writeBatch(List.of(record)));
        }
        verifyNoInteractions(dynamoDbClient);
    }

    private DynamoTargetConnector retryingConnector(int retries) throws Exception {
        DynamoTargetConnector result = spy(new DynamoTargetConnector(dynamoDbClient, converter, plan(),
                new DynamoBatchWriteProperties(retries, Duration.ofMillis(10), Duration.ofMillis(30))));
        doNothing().when(result).sleep(any(Duration.class));
        return result;
    }

    private void stubBatchSuccess() {
        when(dynamoDbClient.batchWriteItem(any(BatchWriteItemRequest.class)))
                .thenReturn(BatchWriteItemResponse.builder().build());
    }

    private List<BatchWriteItemRequest> captureBatches(int count) {
        ArgumentCaptor<BatchWriteItemRequest> captor = ArgumentCaptor.forClass(BatchWriteItemRequest.class);
        verify(dynamoDbClient, times(count)).batchWriteItem(captor.capture());
        return captor.getAllValues();
    }

    private List<LogicalRecord> records(int count) {
        List<LogicalRecord> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(new LogicalRecord(Map.of("id", new LogicalValue.StringValue("P" + i))));
        }
        return result;
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
