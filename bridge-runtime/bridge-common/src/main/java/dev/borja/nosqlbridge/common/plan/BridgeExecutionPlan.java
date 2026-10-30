package dev.borja.nosqlbridge.common.plan;

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
