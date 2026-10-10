package dev.borja.nosqlbridge.runtime.plan;

import java.util.List;

public record BridgeExecutionPlan(
        int planVersion,
        String bridgeName,
        ConnectorPlan source,
        ConnectorPlan target,
        String unmappedPolicy,
        String nullPolicy,
        List<MappingPlan> mappings
) {
}
