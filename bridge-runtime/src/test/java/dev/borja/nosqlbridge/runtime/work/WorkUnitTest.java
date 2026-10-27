package dev.borja.nosqlbridge.runtime.work;


import java.util.UUID;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class WorkUnitTest {

    private final SourcePartition partition = new SourcePartition("test-range", Map.of("upper", "10"));

    @Test
    void acceptsValidIds() {
        MigrationId migrationId = new MigrationId(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
        );
        WorkUnitId workUnitId = new WorkUnitId(0);

        WorkUnit unit = new WorkUnit(migrationId, workUnitId, partition);

        assertEquals(migrationId, unit.migrationId());
        assertEquals(workUnitId, unit.workUnitId());
        assertEquals(partition, unit.sourcePartition());
    }

    @Test
    void rejectsNullIds() {
        MigrationId migrationId = new MigrationId(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
        );
        WorkUnitId workUnitId = new WorkUnitId(0);

        assertThrows(IllegalArgumentException.class,
                () -> new WorkUnit(null, workUnitId, partition));

        assertThrows(IllegalArgumentException.class,
                () -> new WorkUnit(migrationId, null, partition));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkUnit(migrationId, workUnitId, null));
    }

    @Test
    void preservesIdentityAcrossJsonAndHashSetLookup() {
        MigrationId migrationId = new MigrationId(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
        WorkUnit unit = new WorkUnit(migrationId, new WorkUnitId(0), partition);
        ObjectMapper mapper = new ObjectMapper();

        WorkUnit restored = mapper.readValue(mapper.writeValueAsString(unit), WorkUnit.class);

        assertEquals(unit, restored);
        assertEquals(unit.hashCode(), restored.hashCode());
        Set<WorkUnit> units = new HashSet<>();
        units.add(unit);
        units.add(restored);
        assertEquals(1, units.size());
        assertNotEquals(unit, new WorkUnit(migrationId, new WorkUnitId(1), partition));
        assertNotEquals(unit, new WorkUnit(
                new MigrationId(UUID.fromString("550e8400-e29b-41d4-a716-446655440001")),
                new WorkUnitId(0), partition));
    }
}
