package dev.borja.nosqlbridge.runtime.target.dynamodb;

import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import dev.borja.nosqlbridge.runtime.plan.ConnectorPlan;
import dev.borja.nosqlbridge.runtime.target.TargetConnector;
import dev.borja.nosqlbridge.runtime.target.TargetConnectorFactory;

public class DynamoTargetConnectorFactory implements TargetConnectorFactory {
    private final ObjectProvider<DynamoDbClient> client;
    private final DynamoLogicalValueConverter converter;
    private final DynamoBatchWriteProperties batchWriteProperties;

    public DynamoTargetConnectorFactory(ObjectProvider<DynamoDbClient> client, DynamoLogicalValueConverter converter) {
        this(client, converter, new DynamoBatchWriteProperties(null, null, null));
    }

    public DynamoTargetConnectorFactory(ObjectProvider<DynamoDbClient> client, DynamoLogicalValueConverter converter,
            DynamoBatchWriteProperties batchWriteProperties) {
        this.client = client;
        this.converter = converter;
        this.batchWriteProperties = batchWriteProperties;
    }

    @Override
    public String technology() {
        return "dynamodb";
    }

    @Override
    public TargetConnector create(ConnectorPlan plan) {
        return new DynamoTargetConnector(client.getObject(), converter, plan, batchWriteProperties);
    }
}
