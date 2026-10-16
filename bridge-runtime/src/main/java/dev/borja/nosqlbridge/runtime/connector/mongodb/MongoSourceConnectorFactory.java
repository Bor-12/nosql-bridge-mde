package dev.borja.nosqlbridge.runtime.connector.mongodb;

import org.springframework.beans.factory.ObjectProvider;

import com.mongodb.client.MongoClient;

import dev.borja.nosqlbridge.runtime.connector.SourceConnector;
import dev.borja.nosqlbridge.runtime.connector.SourceConnectorFactory;
import dev.borja.nosqlbridge.runtime.plan.ConnectorPlan;

public class MongoSourceConnectorFactory implements SourceConnectorFactory {
    private final ObjectProvider<MongoClient> client;
    private final MongoLogicalValueConverter converter;

    public MongoSourceConnectorFactory(ObjectProvider<MongoClient> client, MongoLogicalValueConverter converter) {
        this.client = client;
        this.converter = converter;
    }

    @Override
    public String technology() {
        return "mongodb";
    }

    @Override
    public SourceConnector create(ConnectorPlan plan) {
        return new MongoSourceConnector(client.getObject(), converter, plan);
    }
}
