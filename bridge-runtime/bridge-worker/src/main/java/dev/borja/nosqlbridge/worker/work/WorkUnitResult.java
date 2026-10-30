package dev.borja.nosqlbridge.worker.work;

import dev.borja.nosqlbridge.common.work.MigrationId;
import dev.borja.nosqlbridge.common.work.WorkUnitId;

public record WorkUnitResult(MigrationId migrationId, WorkUnitId workUnitId, long recordsRead, long recordsWritten) {

    public WorkUnitResult {
        if (migrationId == null) {
            throw new IllegalArgumentException("Migration id must not be null.");
        }
        if (workUnitId == null) {
            throw new IllegalArgumentException("Work unit id must not be null.");
        }
        if (recordsRead < 0 || recordsWritten < 0 || recordsWritten > recordsRead) {
            throw new IllegalArgumentException("Record counts must satisfy 0 <= recordsWritten <= recordsRead.");
        }
    }
}
