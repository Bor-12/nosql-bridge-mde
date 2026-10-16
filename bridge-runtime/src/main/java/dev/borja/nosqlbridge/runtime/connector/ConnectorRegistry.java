package dev.borja.nosqlbridge.runtime.connector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConnectorRegistry {
    private final Map<String, SourceConnectorFactory> sources = new HashMap<>();
    private final Map<String, TargetConnectorFactory> targets = new HashMap<>();

    public ConnectorRegistry(List<SourceConnectorFactory> sourceFactories,
            List<TargetConnectorFactory> targetFactories) {
        for (SourceConnectorFactory factory : sourceFactories) {
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

    public SourceConnectorFactory sourceFactory(String technology) {
        SourceConnectorFactory factory = sources.get(technology);
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
