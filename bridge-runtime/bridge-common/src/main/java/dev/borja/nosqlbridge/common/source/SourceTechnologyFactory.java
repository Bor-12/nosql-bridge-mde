package dev.borja.nosqlbridge.common.source;

import dev.borja.nosqlbridge.common.plan.ConnectorPlan;
import dev.borja.nosqlbridge.common.source.connector.SourceConnector;
import dev.borja.nosqlbridge.common.source.partitioning.SourcePartitioner;

public interface SourceTechnologyFactory {
    String technology();
    SourceConnector createConnector(ConnectorPlan plan);

    default SourcePartitioner createPartitioner(ConnectorPlan plan) {
        throw new UnsupportedOperationException("Source technology does not support partitioning.");
    }
}
