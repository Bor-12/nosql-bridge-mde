package dev.borja.nosqlbridge.common.plan;

public record MappingPlan(
        String source,
        String target,
        String transformation,
        MappingDefaultValue defaultValue
) {
}
