package dev.borja.nosqlbridge.runtime.plan;

import java.util.Map;

public record ConnectorPlan(
        String technology,
        Map<String, String> config
) {
}
