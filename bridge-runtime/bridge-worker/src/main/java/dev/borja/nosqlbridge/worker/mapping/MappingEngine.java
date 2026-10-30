package dev.borja.nosqlbridge.worker.mapping;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.borja.nosqlbridge.worker.model.LogicalPathResolver;
import dev.borja.nosqlbridge.common.model.LogicalRecord;
import dev.borja.nosqlbridge.common.model.LogicalValue;
import dev.borja.nosqlbridge.common.plan.MappingPlan;
import dev.borja.nosqlbridge.worker.transformation.TransformationEngine;

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

    public LogicalRecord map(
            LogicalRecord source,
            List<MappingPlan> mappings,
            String nullPolicy,
            String unmappedPolicy) {
        validateNullPolicy(nullPolicy);
        validateUnmappedPolicy(unmappedPolicy);
        Map<String, LogicalValue> fields = new HashMap<>();
        if ("preserve".equals(unmappedPolicy)) {
            fields.putAll(source.fields());
        }
        for (MappingPlan mapping : mappings) {
            if ("preserve".equals(unmappedPolicy)) {
                fields.remove(getTopLevelSourceField(mapping.source()));
            }
            LogicalValue value = pathResolver.resolve(source, mapping.source());
            if ((value == null || value instanceof LogicalValue.NullValue)
                    && mapping.defaultValue() != null) {
                value = defaultValueConverter.convert(mapping.defaultValue());
            }
            if (value != null
                    && (!(value instanceof LogicalValue.NullValue) || "preserve".equals(nullPolicy))) {
                LogicalValue transformedValue = transformationEngine.transform(
                        mapping.transformation(),
                        value);
                fields.put(mapping.target(), transformedValue);
            }
        }
        return new LogicalRecord(fields);
    }

    private void validateNullPolicy(String nullPolicy) {
        if (!"preserve".equals(nullPolicy) && !"omit".equals(nullPolicy)) {
            throw new IllegalArgumentException("Unsupported null policy: " + nullPolicy);
        }
    }

    private void validateUnmappedPolicy(String unmappedPolicy) {
        if (!"preserve".equals(unmappedPolicy) && !"drop".equals(unmappedPolicy)) {
            throw new IllegalArgumentException("Unsupported unmapped policy: " + unmappedPolicy);
        }
    }

    private String getTopLevelSourceField(String sourcePath) {
        int separatorIndex = sourcePath.indexOf('.');
        if (separatorIndex < 0) {
            return sourcePath;
        }
        return sourcePath.substring(0, separatorIndex);
    }
}
