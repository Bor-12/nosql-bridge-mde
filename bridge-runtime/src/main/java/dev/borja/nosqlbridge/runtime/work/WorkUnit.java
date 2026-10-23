package dev.borja.nosqlbridge.runtime.work;


public record WorkUnit(MigrationId migrationId, WorkUnitId workUnitId, SourcePartition sourcePartition) {

    public WorkUnit {
        if (migrationId == null) {
            throw new IllegalArgumentException("Migration id must not be null.");
        }
        if (workUnitId == null) {
            throw new IllegalArgumentException("Work unit id must not be null.");
        }
        if (sourcePartition == null) {
            throw new IllegalArgumentException("Source partition must not be null.");
        }
    }
}
