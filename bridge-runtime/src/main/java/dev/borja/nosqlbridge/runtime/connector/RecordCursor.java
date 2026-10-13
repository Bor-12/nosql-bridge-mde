package dev.borja.nosqlbridge.runtime.connector;

import dev.borja.nosqlbridge.runtime.model.LogicalRecord;

/** Incremental record reader whose resources are owned by the caller. */
public interface RecordCursor extends AutoCloseable {

    boolean hasNext();

    LogicalRecord next();

    @Override
    void close();
}
