package dev.borja.nosqlbridge.runtime.mapping;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.borja.nosqlbridge.runtime.model.LogicalPathResolver;
import dev.borja.nosqlbridge.runtime.model.LogicalRecord;
import dev.borja.nosqlbridge.runtime.model.LogicalValue;
import dev.borja.nosqlbridge.runtime.plan.MappingPlan;

public class MappingEngine {

    private final LogicalPathResolver pathResolver;

    public MappingEngine(LogicalPathResolver pathResolver) {
        this.pathResolver = pathResolver;
    }

    public LogicalRecord map(LogicalRecord source, List<MappingPlan> mappings) {
        Map<String, LogicalValue> fields = new HashMap<>();
        for (MappingPlan mapping : mappings) {
            LogicalValue value = pathResolver.resolve(source, mapping.source());
            if (value != null) {
                fields.put(mapping.target(), value);
            }
        }
        return new LogicalRecord(fields);
    }
}
