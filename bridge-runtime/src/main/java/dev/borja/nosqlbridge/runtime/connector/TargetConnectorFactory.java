package dev.borja.nosqlbridge.runtime.connector;

import dev.borja.nosqlbridge.runtime.plan.ConnectorPlan;

public interface TargetConnectorFactory {
    String technology();
    TargetConnector create(ConnectorPlan plan);
}
