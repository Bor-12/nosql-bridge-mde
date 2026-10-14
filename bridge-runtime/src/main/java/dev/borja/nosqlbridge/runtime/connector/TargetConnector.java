package dev.borja.nosqlbridge.runtime.connector;

import dev.borja.nosqlbridge.runtime.model.LogicalRecord;

public interface TargetConnector {

    void write(LogicalRecord record);
}
