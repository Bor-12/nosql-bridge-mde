package dev.borja.nosqlbridge.runtime.plan;

public record MappingPlan(
        String source,
        String target,
        String transformation,
        MappingDefaultValue defaultValue
) {
}
