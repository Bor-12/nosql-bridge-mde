package dev.borja.nosqlbridge.runtime.target.dynamodb;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import dev.borja.nosqlbridge.runtime.model.LogicalRecord;
import dev.borja.nosqlbridge.runtime.plan.ConnectorPlan;
import dev.borja.nosqlbridge.runtime.target.TargetConnector;

public class DynamoTargetConnector implements TargetConnector {

    private final DynamoDbClient dynamoDbClient;
    private final DynamoLogicalValueConverter converter;
    private final String tableName;
    private final String partitionKey;
    private final String sortKey;
    private final DynamoBatchWriteProperties batchWriteProperties;

    public DynamoTargetConnector(DynamoDbClient dynamoDbClient, DynamoLogicalValueConverter converter,
            ConnectorPlan plan) {
        this(dynamoDbClient, converter, plan, new DynamoBatchWriteProperties(null, null, null));
    }

    public DynamoTargetConnector(DynamoDbClient dynamoDbClient, DynamoLogicalValueConverter converter,
            ConnectorPlan plan, DynamoBatchWriteProperties batchWriteProperties) {
        if (dynamoDbClient == null) {
            throw new IllegalArgumentException("DynamoDB client must not be null.");
        }
        if (converter == null) {
            throw new IllegalArgumentException("DynamoDB logical value converter must not be null.");
        }
        if (plan == null) {
            throw new IllegalArgumentException("DynamoDB connector plan must not be null.");
        }
        if (batchWriteProperties == null) {
            throw new IllegalArgumentException("DynamoDB batch write properties must not be null.");
        }
        if (!"dynamodb".equals(plan.technology())) {
            throw new IllegalArgumentException("DynamoDB connector technology must be dynamodb.");
        }
        this.tableName = requiredConfig(plan.config(), "table");
        this.partitionKey = requiredConfig(plan.config(), "partitionKey");
        this.sortKey = plan.config().containsKey("sortKey") ? requiredConfig(plan.config(), "sortKey") : null;
        if (partitionKey.equals(sortKey)) {
            throw new IllegalArgumentException("DynamoDB partitionKey and sortKey must be different.");
        }
        this.dynamoDbClient = dynamoDbClient;
        this.converter = converter;
        this.batchWriteProperties = batchWriteProperties;
    }

    @Override
    public void write(LogicalRecord record) {
        Map<String, AttributeValue> attributes = convertRecord(record);
        validateKey(attributes, partitionKey, 2048);

        if (sortKey != null) {
            validateKey(attributes, sortKey, 1024);
        }
        PutItemRequest request = PutItemRequest.builder()
                .tableName(tableName)
                .item(attributes)
                .build();
        dynamoDbClient.putItem(request);
    }

    @Override
    public void writeBatch(List<LogicalRecord> records) {
        if (records == null) {
            throw new IllegalArgumentException("Logical records must not be null.");
        }
        List<WriteRequest> batch = new ArrayList<>();
        Set<List<Object>> keys = new HashSet<>();
        long envelopeBytes = DynamoBatchWriteLimits.envelopeBytes(tableName);
        long requestBytes = envelopeBytes;
        for (LogicalRecord record : records) {
            Map<String, AttributeValue> attributes = convertRecord(record);
            Object partition = validateKey(attributes, partitionKey, 2048);
            List<Object> key = sortKey == null ? List.of(partition) : List.of(partition, validateKey(attributes, sortKey, 1024));
            long putBytes = checkedPutBytes(attributes, envelopeBytes);
            if (!batch.isEmpty() && (batch.size() == DynamoBatchWriteLimits.MAX_OPERATIONS
                    || requestBytes + putBytes + 1 > DynamoBatchWriteLimits.MAX_REQUEST_BYTES || keys.contains(key))) {
                writePending(batch);
                batch.clear();
                keys.clear();
                requestBytes = envelopeBytes;
            }
            batch.add(WriteRequest.builder().putRequest(PutRequest.builder().item(attributes).build()).build());
            keys.add(key);
            requestBytes += putBytes + (batch.size() > 1 ? 1 : 0);
        }
        if (!batch.isEmpty()) writePending(batch);
    }

    private Map<String, AttributeValue> convertRecord(LogicalRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("Logical record must not be null.");
        }
        return converter.toAttributeMap(record);
    }

    private long checkedPutBytes(Map<String, AttributeValue> attributes, long envelopeBytes) {
        if (DynamoBatchWriteLimits.itemBytes(attributes) > DynamoBatchWriteLimits.MAX_ITEM_BYTES) {
            throw new IllegalArgumentException("DynamoDB batch item exceeds the conservative 400 KiB size bound.");
        }
        long putBytes = DynamoBatchWriteLimits.putBytes(attributes);
        if (envelopeBytes + putBytes > DynamoBatchWriteLimits.MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("DynamoDB batch item exceeds the 16 MiB request size bound.");
        }
        return putBytes;
    }

    private Object keyValue(AttributeValue value, String name, int maxBytes) {
        if (value.s() != null) {
            int length = value.s().getBytes(StandardCharsets.UTF_8).length;
            if (length > 0 && length <= maxBytes) return value.s();
        } else if (value.b() != null) {
            int length = value.b().asByteBuffer().remaining();
            if (length > 0 && length <= maxBytes) return value.b();
        } else if (value.n() != null) {
            return new BigDecimal(value.n()).stripTrailingZeros();
        }
        throw new IllegalArgumentException("DynamoDB batch key must be a nonempty string/binary within its size limit or a number: " + name);
    }

    private void writePending(List<WriteRequest> batch) {
        List<WriteRequest> pending = batch;
        int retries = 0;
        long backoffMillis = batchWriteProperties.initialBackoff().toMillis();
        long maxBackoffMillis = batchWriteProperties.maxBackoff().toMillis();
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Interrupted while writing DynamoDB batch.");
            }
            BatchWriteItemResponse response = dynamoDbClient.batchWriteItem(BatchWriteItemRequest.builder()
                    .requestItems(Map.of(tableName, pending)).build());
            pending = response.unprocessedItems().getOrDefault(tableName, List.of());
            if (pending.isEmpty()) return;
            if (retries == batchWriteProperties.maxRetries()) {
                throw new IllegalStateException("DynamoDB batch write exhausted " + retries + " retries; "
                        + pending.size() + " unprocessed items remain for table " + tableName + ".");
            }
            try {
                sleep(Duration.ofMillis(ThreadLocalRandom.current().nextLong(backoffMillis)));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while retrying DynamoDB batch writes.", exception);
            }
            retries++;
            backoffMillis = backoffMillis > maxBackoffMillis / 2
                    ? maxBackoffMillis : Math.min(maxBackoffMillis, backoffMillis * 2);
        }
    }

    void sleep(Duration delay) throws InterruptedException {
        Thread.sleep(delay);
    }

    private String requiredConfig(Map<String, String> config, String name) {
        String value = config == null ? null : config.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("DynamoDB " + name + " must not be blank.");
        }
        return value;
    }

    private Object validateKey(
            Map<String, AttributeValue> attributes,
            String name,
            int maxBytes) {

        if (!attributes.containsKey(name)) {
            throw new IllegalArgumentException(
                    "DynamoDB item is missing key: " + name);
        }

        AttributeValue value = attributes.get(name);

        if (value == null || Boolean.TRUE.equals(value.nul())) {
            throw new IllegalArgumentException(
                    "DynamoDB key must not be NULL: " + name);
        }

        return keyValue(value, name, maxBytes);
    }
}
