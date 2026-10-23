package dev.borja.nosqlbridge.runtime.source.connector;

import java.util.Iterator;

import dev.borja.nosqlbridge.runtime.model.LogicalRecord;

/** Incremental record reader whose resources are owned by the caller. */
public interface RecordCursor extends Iterator<LogicalRecord>, AutoCloseable {

    @Override
    void close();
}
