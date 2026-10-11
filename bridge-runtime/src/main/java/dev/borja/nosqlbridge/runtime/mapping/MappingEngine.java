package dev.borja.nosqlbridge.runtime.mapping;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.borja.nosqlbridge.runtime.model.LogicalPathResolver;
import dev.borja.nosqlbridge.runtime.model.LogicalRecord;
import dev.borja.nosqlbridge.runtime.model.LogicalValue;
import dev.borja.nosqlbridge.runtime.plan.MappingPlan;
import dev.borja.nosqlbridge.runtime.transformation.TransformationEngine;

public class MappingEngine {

    private final LogicalPathResolver pathResolver;
    private final TransformationEngine transformationEngine;
    private final DefaultValueConverter defaultValueConverter;

    public MappingEngine(
            LogicalPathResolver pathResolver,
            TransformationEngine transformationEngine,
            DefaultValueConverter defaultValueConverter) {
        this.pathResolver = pathResolver;
        this.transformationEngine = transformationEngine;
        this.defaultValueConverter = defaultValueConverter;
    }

    public LogicalRecord map(LogicalRecord source, List<MappingPlan> mappings) {
        Map<String, LogicalValue> fields = new HashMap<>();
        for (MappingPlan mapping : mappings) {
            LogicalValue value = pathResolver.resolve(source, mapping.source());
            if ((value == null || value instanceof LogicalValue.NullValue)
                    && mapping.defaultValue() != null) {
                value = defaultValueConverter.convert(mapping.defaultValue());
            }
            if (value != null) {
                LogicalValue transformedValue = transformationEngine.transform(
                        mapping.transformation(),
                        value);
                fields.put(mapping.target(), transformedValue);
            }
        }
        return new LogicalRecord(fields);
    }
}
