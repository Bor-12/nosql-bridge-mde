package dev.borja.nosqlbridge.runtime.work;

public record WorkUnitId(long value) {

    public WorkUnitId {
        if (value < 0) {
            throw new IllegalArgumentException(
                    "Work unit id must not be negative."
            );
        }
    }
}
