package dev.borja.nosqlbridge.runtime.work;


public record WorkUnit(MigrationId migrationId, WorkUnitId workUnitId) {

    public WorkUnit {
        if (migrationId == null) {
            throw new IllegalArgumentException("Migration id must not be null.");
        }
        if (workUnitId == null) {
            throw new IllegalArgumentException("Work unit id must not be null.");
        }
    }
}
