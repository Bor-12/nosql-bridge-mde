package dev.borja.nosqlbridge.common.plan;

import java.util.Map;

public record ConnectorPlan(
        String technology,
        Map<String, String> config
) {
}
