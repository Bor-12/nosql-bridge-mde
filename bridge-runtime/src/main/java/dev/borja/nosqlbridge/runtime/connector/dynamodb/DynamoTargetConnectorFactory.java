package dev.borja.nosqlbridge.runtime.connector.dynamodb;

import org.springframework.beans.factory.ObjectProvider;

import dev.borja.nosqlbridge.runtime.connector.TargetConnector;
import dev.borja.nosqlbridge.runtime.connector.TargetConnectorFactory;
import dev.borja.nosqlbridge.runtime.plan.ConnectorPlan;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

public class DynamoTargetConnectorFactory implements TargetConnectorFactory {
    private final ObjectProvider<DynamoDbClient> client;
    private final DynamoLogicalValueConverter converter;

    public DynamoTargetConnectorFactory(ObjectProvider<DynamoDbClient> client, DynamoLogicalValueConverter converter) {
        this.client = client;
        this.converter = converter;
    }

    @Override
    public String technology() {
        return "dynamodb";
    }

    @Override
    public TargetConnector create(ConnectorPlan plan) {
        return new DynamoTargetConnector(client.getObject(), converter, plan);
    }
}
