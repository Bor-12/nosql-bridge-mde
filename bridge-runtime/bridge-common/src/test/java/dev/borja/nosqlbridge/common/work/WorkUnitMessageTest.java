package dev.borja.nosqlbridge.common.work;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.borja.nosqlbridge.common.plan.BridgeExecutionPlan;
import dev.borja.nosqlbridge.common.plan.ConnectorPlan;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkUnitMessageTest {

    private final WorkUnit unit = new WorkUnit(
            new MigrationId(UUID.fromString("550e8400-e29b-41d4-a716-446655440000")), new WorkUnitId(0),
            new SourcePartition("test-range", Map.of("upper", "10")));
    private final BridgeExecutionPlan plan = new BridgeExecutionPlan(1, "test",
            new ConnectorPlan("source", Map.of()), new ConnectorPlan("target", Map.of()),
            "drop", "omit", List.of());

    @Test
    void retainsTheWorkUnitAndItsExecutionPlan() {
        WorkUnitMessage message = new WorkUnitMessage(unit, plan);

        assertSame(unit, message.workUnit());
        assertSame(plan, message.plan());
    }

    @Test
    void rejectsMissingWorkUnitOrPlan() {
        assertThrows(IllegalArgumentException.class, () -> new WorkUnitMessage(null, plan));
        assertThrows(IllegalArgumentException.class, () -> new WorkUnitMessage(unit, null));
    }
}
