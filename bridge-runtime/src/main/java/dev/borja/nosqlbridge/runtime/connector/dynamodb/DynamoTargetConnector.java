package dev.borja.nosqlbridge.runtime.connector.dynamodb;

import java.util.Map;

import dev.borja.nosqlbridge.runtime.connector.TargetConnector;
import dev.borja.nosqlbridge.runtime.model.LogicalRecord;
import dev.borja.nosqlbridge.runtime.plan.ConnectorPlan;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

public class DynamoTargetConnector implements TargetConnector {

    private final DynamoDbClient dynamoDbClient;
    private final DynamoLogicalValueConverter converter;
    private final String tableName;
    private final String partitionKey;
    private final String sortKey;

    public DynamoTargetConnector(DynamoDbClient dynamoDbClient, DynamoLogicalValueConverter converter,
            ConnectorPlan plan) {
        if (dynamoDbClient == null) {
            throw new IllegalArgumentException("DynamoDB client must not be null.");
        }
        if (converter == null) {
            throw new IllegalArgumentException("DynamoDB logical value converter must not be null.");
        }
        if (plan == null) {
            throw new IllegalArgumentException("DynamoDB connector plan must not be null.");
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
    }

    @Override
    public void write(LogicalRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("Logical record must not be null.");
        }
        Map<String, AttributeValue> attributes = converter.toAttributeMap(record);
        validateKey(attributes, partitionKey);
        if (sortKey != null) {
            validateKey(attributes, sortKey);
        }
        PutItemRequest request = PutItemRequest.builder()
                .tableName(tableName)
                .item(attributes)
                .build();
        dynamoDbClient.putItem(request);
    }

    private String requiredConfig(Map<String, String> config, String name) {
        String value = config == null ? null : config.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("DynamoDB " + name + " must not be blank.");
        }
        return value;
    }

    private void validateKey(Map<String, AttributeValue> attributes, String name) {
        if (!attributes.containsKey(name)) {
            throw new IllegalArgumentException("DynamoDB item is missing key: " + name);
        }
        AttributeValue value = attributes.get(name);
        if (value == null || Boolean.TRUE.equals(value.nul())) {
            throw new IllegalArgumentException("DynamoDB key must not be NULL: " + name);
        }
    }
}
