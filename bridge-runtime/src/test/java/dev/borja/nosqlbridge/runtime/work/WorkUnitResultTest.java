package dev.borja.nosqlbridge.runtime.work;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkUnitResultTest {

    private final MigrationId migrationId = new MigrationId(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
    private final WorkUnitId workUnitId = new WorkUnitId(0);

    @Test
    void retainsIdentityAndLongCounters() {
        long count = (long) Integer.MAX_VALUE + 1;
        WorkUnitResult result = new WorkUnitResult(migrationId, workUnitId, count, count);

        assertEquals(migrationId, result.migrationId());
        assertEquals(workUnitId, result.workUnitId());
        assertEquals(count, result.recordsRead());
        assertEquals(count, result.recordsWritten());
        assertEquals(new WorkUnitResult(migrationId, workUnitId, count, count), result);
    }

    @Test
    void rejectsMissingIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new WorkUnitResult(null, workUnitId, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorkUnitResult(migrationId, null, 0, 0));
    }

    @ParameterizedTest
    @CsvSource({"-1, 0", "1, -1", "1, 2"})
    void rejectsInconsistentCounters(long read, long written) {
        assertThrows(IllegalArgumentException.class,
                () -> new WorkUnitResult(migrationId, workUnitId, read, written));
    }
}
