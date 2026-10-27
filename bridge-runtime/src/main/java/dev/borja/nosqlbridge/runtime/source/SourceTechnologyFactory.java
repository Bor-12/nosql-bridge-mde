package dev.borja.nosqlbridge.runtime.source;

import dev.borja.nosqlbridge.runtime.plan.ConnectorPlan;
import dev.borja.nosqlbridge.runtime.source.connector.SourceConnector;
import dev.borja.nosqlbridge.runtime.source.partitioning.SourcePartitioner;

public interface SourceTechnologyFactory {
    String technology();
    SourceConnector createConnector(ConnectorPlan plan);

    default SourcePartitioner createPartitioner(ConnectorPlan plan) {
        throw new UnsupportedOperationException("Source technology does not support partitioning.");
    }
}
