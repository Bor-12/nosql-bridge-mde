package dev.borja.nosqlbridge.runtime.work;


import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkUnitTest {

    @Test
    void acceptsValidIds() {
        MigrationId migrationId = new MigrationId(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
        );
        WorkUnitId workUnitId = new WorkUnitId(0);

        WorkUnit unit = new WorkUnit(migrationId, workUnitId);

        assertEquals(migrationId, unit.migrationId());
        assertEquals(workUnitId, unit.workUnitId());
    }

    @Test
    void rejectsNullIds() {
        MigrationId migrationId = new MigrationId(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
        );
        WorkUnitId workUnitId = new WorkUnitId(0);

        assertThrows(IllegalArgumentException.class,
                () -> new WorkUnit(null, workUnitId));

        assertThrows(IllegalArgumentException.class,
                () -> new WorkUnit(migrationId, null));
    }
}
