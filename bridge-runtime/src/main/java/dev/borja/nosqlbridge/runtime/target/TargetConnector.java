package dev.borja.nosqlbridge.runtime.target;

import dev.borja.nosqlbridge.runtime.model.LogicalRecord;

public interface TargetConnector {

    void write(LogicalRecord record);
}
