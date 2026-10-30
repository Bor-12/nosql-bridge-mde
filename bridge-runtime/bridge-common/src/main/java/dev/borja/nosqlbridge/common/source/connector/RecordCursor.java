package dev.borja.nosqlbridge.common.source.connector;

import java.util.Iterator;

import dev.borja.nosqlbridge.common.model.LogicalRecord;

public interface RecordCursor extends Iterator<LogicalRecord>, AutoCloseable {

    @Override
    void close();
}
