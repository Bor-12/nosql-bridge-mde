package dev.borja.nosqlbridge.mongodb;

import com.mongodb.client.MongoClient;
import org.springframework.beans.factory.ObjectProvider;

import dev.borja.nosqlbridge.common.plan.ConnectorPlan;
import dev.borja.nosqlbridge.common.source.SourceTechnologyFactory;
import dev.borja.nosqlbridge.common.source.connector.SourceConnector;
import dev.borja.nosqlbridge.common.source.partitioning.SourcePartitioner;

public class MongoSourceTechnologyFactory implements SourceTechnologyFactory {
    private final ObjectProvider<MongoClient> client;
    private final MongoLogicalValueConverter converter;

    public MongoSourceTechnologyFactory(ObjectProvider<MongoClient> client, MongoLogicalValueConverter converter) {
        this.client = client;
        this.converter = converter;
    }

    @Override
    public String technology() {
        return "mongodb";
    }

    @Override
    public SourceConnector createConnector(ConnectorPlan plan) {
        return new MongoSourceConnector(client.getObject(), converter, plan);
    }

    @Override
    public SourcePartitioner createPartitioner(ConnectorPlan plan) {
        if (plan == null || !"mongodb".equals(plan.technology())) {
            throw new IllegalArgumentException("MongoDB connector plan must specify technology mongodb.");
        }
        String database = requiredConfig(plan, "database");
        String collection = requiredConfig(plan, "collection");
        return new MongoSourcePartitioner(client.getObject().getDatabase(database).getCollection(collection));
    }

    private String requiredConfig(ConnectorPlan plan, String name) {
        String value = plan.config() == null ? null : plan.config().get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("MongoDB " + name + " must not be blank.");
        }
        return value;
    }
}
