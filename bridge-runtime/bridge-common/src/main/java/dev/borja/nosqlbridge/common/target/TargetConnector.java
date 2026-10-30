package dev.borja.nosqlbridge.common.target;

import java.util.List;

import dev.borja.nosqlbridge.common.model.LogicalRecord;

public interface TargetConnector {

    void write(LogicalRecord record);

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
