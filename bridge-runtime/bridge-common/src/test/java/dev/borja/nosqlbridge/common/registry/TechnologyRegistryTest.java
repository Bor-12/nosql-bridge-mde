package dev.borja.nosqlbridge.common.registry;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.borja.nosqlbridge.common.plan.ConnectorPlan;
import dev.borja.nosqlbridge.common.source.SourceTechnologyFactory;
import dev.borja.nosqlbridge.common.source.connector.SourceConnector;
import dev.borja.nosqlbridge.common.target.TargetConnector;
import dev.borja.nosqlbridge.common.target.TargetConnectorFactory;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TechnologyRegistryTest {
    @Test
    void resolvesNewTechnologyIndependentlyForEachRole() {
        SourceTechnologyFactory source = new SourceFactory("custom");
        TargetConnectorFactory target = new TargetFactory("custom");
        TechnologyRegistry registry = new TechnologyRegistry(List.of(source), List.of(target));

        assertSame(source, registry.sourceFactory("custom"));
        assertSame(target, registry.targetFactory("custom"));
    }

    @Test
    void sourceSupportDoesNotImplyTargetSupport() {
        TechnologyRegistry registry = new TechnologyRegistry(List.of(new SourceFactory("custom")), List.of());

        assertThrows(IllegalArgumentException.class, () -> registry.targetFactory("custom"));
        assertThrows(IllegalArgumentException.class, () -> registry.sourceFactory("unknown"));
    }

    @Test
    void targetSupportDoesNotImplySourceSupport() {
        TechnologyRegistry registry = new TechnologyRegistry(List.of(), List.of(new TargetFactory("custom")));

        assertThrows(IllegalArgumentException.class, () -> registry.sourceFactory("custom"));
        assertThrows(IllegalArgumentException.class, () -> registry.targetFactory("unknown"));
    }

    @Test
    void rejectsAmbiguousSourceRegistration() {
        assertThrows(IllegalArgumentException.class, () -> new TechnologyRegistry(
                List.of(new SourceFactory("custom"), new SourceFactory("custom")), List.of()));
    }

    @Test
    void rejectsAmbiguousTargetRegistration() {
        assertThrows(IllegalArgumentException.class, () -> new TechnologyRegistry(List.of(),
                List.of(new TargetFactory("custom"), new TargetFactory("custom"))));
    }

    private record SourceFactory(String technology) implements SourceTechnologyFactory {
        @Override
        public SourceConnector createConnector(ConnectorPlan plan) {
            throw new AssertionError("Registry lookup must not create a connector.");
        }
    }

    private record TargetFactory(String technology) implements TargetConnectorFactory {
        @Override
        public TargetConnector create(ConnectorPlan plan) {
            throw new AssertionError("Registry lookup must not create a connector.");
        }
    }
}
