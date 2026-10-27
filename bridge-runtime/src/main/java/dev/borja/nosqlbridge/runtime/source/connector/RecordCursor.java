package dev.borja.nosqlbridge.runtime.source.connector;

import java.util.Iterator;

import dev.borja.nosqlbridge.runtime.model.LogicalRecord;

public interface RecordCursor extends Iterator<LogicalRecord>, AutoCloseable {

    @Override
    void close();
}
