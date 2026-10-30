package dev.borja.nosqlbridge.common.target;

import dev.borja.nosqlbridge.common.plan.ConnectorPlan;

public interface TargetConnectorFactory {
    String technology();
    TargetConnector create(ConnectorPlan plan);
}
