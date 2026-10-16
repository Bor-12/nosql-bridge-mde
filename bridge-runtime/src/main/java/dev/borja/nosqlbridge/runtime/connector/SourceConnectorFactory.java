package dev.borja.nosqlbridge.runtime.connector;

import dev.borja.nosqlbridge.runtime.plan.ConnectorPlan;

public interface SourceConnectorFactory {
    String technology();
    SourceConnector create(ConnectorPlan plan);
}
