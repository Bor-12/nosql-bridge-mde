package dev.borja.nosqlbridge.runtime.source.connector;

import dev.borja.nosqlbridge.runtime.work.SourcePartition;

public interface SourceConnector {

    RecordCursor open();

    default RecordCursor open(SourcePartition partition) {
        throw new UnsupportedOperationException("Source connector does not support partitioned reads.");
    }
}
