package dev.borja.nosqlbridge.runtime.connector;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.borja.nosqlbridge.runtime.plan.ConnectorPlan;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConnectorRegistryTest {
    @Test
    void resolvesNewTechnologyIndependentlyForEachRole() {
        SourceConnectorFactory source = new SourceFactory("custom");
        TargetConnectorFactory target = new TargetFactory("custom");
        ConnectorRegistry registry = new ConnectorRegistry(List.of(source), List.of(target));

        assertSame(source, registry.sourceFactory("custom"));
        assertSame(target, registry.targetFactory("custom"));
    }

    @Test
    void sourceSupportDoesNotImplyTargetSupport() {
        ConnectorRegistry registry = new ConnectorRegistry(List.of(new SourceFactory("custom")), List.of());

        assertThrows(IllegalArgumentException.class, () -> registry.targetFactory("custom"));
        assertThrows(IllegalArgumentException.class, () -> registry.sourceFactory("unknown"));
    }

    @Test
    void targetSupportDoesNotImplySourceSupport() {
        ConnectorRegistry registry = new ConnectorRegistry(List.of(), List.of(new TargetFactory("custom")));

        assertThrows(IllegalArgumentException.class, () -> registry.sourceFactory("custom"));
        assertThrows(IllegalArgumentException.class, () -> registry.targetFactory("unknown"));
    }

    @Test
    void rejectsAmbiguousSourceRegistration() {
        assertThrows(IllegalArgumentException.class, () -> new ConnectorRegistry(
                List.of(new SourceFactory("custom"), new SourceFactory("custom")), List.of()));
    }

    @Test
    void rejectsAmbiguousTargetRegistration() {
        assertThrows(IllegalArgumentException.class, () -> new ConnectorRegistry(List.of(),
                List.of(new TargetFactory("custom"), new TargetFactory("custom"))));
    }

    private record SourceFactory(String technology) implements SourceConnectorFactory {
        @Override
        public SourceConnector create(ConnectorPlan plan) {
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
