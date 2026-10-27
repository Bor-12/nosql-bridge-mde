package dev.borja.nosqlbridge.runtime.target;

import java.util.List;

import dev.borja.nosqlbridge.runtime.model.LogicalRecord;

public interface TargetConnector {

    void write(LogicalRecord record);

    /**
     * Returns only after every record has been written. This is not atomic:
     * a failure may leave earlier writes applied. Records retain input order
     * when writing the same key. Implementations may use native batch operations.
     */
    default void writeBatch(List<LogicalRecord> records) {
        if (records == null) {
            throw new IllegalArgumentException("Logical records must not be null.");
        }
        for (LogicalRecord record : records) {
            if (record == null) {
                throw new IllegalArgumentException("Logical record must not be null.");
            }
            write(record);
        }
    }
}
