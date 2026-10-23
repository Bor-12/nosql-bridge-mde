package dev.borja.nosqlbridge.runtime.registry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.borja.nosqlbridge.runtime.source.SourceTechnologyFactory;
import dev.borja.nosqlbridge.runtime.target.TargetConnectorFactory;

public class TechnologyRegistry {
    private final Map<String, SourceTechnologyFactory> sources = new HashMap<>();
    private final Map<String, TargetConnectorFactory> targets = new HashMap<>();

    public TechnologyRegistry(List<SourceTechnologyFactory> sourceFactories,
            List<TargetConnectorFactory> targetFactories) {
        for (SourceTechnologyFactory factory : sourceFactories) {
            if (sources.putIfAbsent(factory.technology(), factory) != null) {
                throw new IllegalArgumentException("Duplicate source connector: " + factory.technology());
            }
        }
        for (TargetConnectorFactory factory : targetFactories) {
            if (targets.putIfAbsent(factory.technology(), factory) != null) {
                throw new IllegalArgumentException("Duplicate target connector: " + factory.technology());
            }
        }
    }

    public SourceTechnologyFactory sourceFactory(String technology) {
        SourceTechnologyFactory factory = sources.get(technology);
        if (factory == null) {
            throw new IllegalArgumentException("Unsupported source technology: " + technology);
        }
        return factory;
    }

    public TargetConnectorFactory targetFactory(String technology) {
        TargetConnectorFactory factory = targets.get(technology);
        if (factory == null) {
            throw new IllegalArgumentException("Unsupported target technology: " + technology);
        }
        return factory;
    }
}
