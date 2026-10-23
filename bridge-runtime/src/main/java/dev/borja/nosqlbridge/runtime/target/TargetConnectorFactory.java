package dev.borja.nosqlbridge.runtime.target;

import dev.borja.nosqlbridge.runtime.plan.ConnectorPlan;

public interface TargetConnectorFactory {
    String technology();
    TargetConnector create(ConnectorPlan plan);
}
