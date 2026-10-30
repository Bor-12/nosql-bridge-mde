package dev.borja.nosqlbridge.common.source.connector;

import dev.borja.nosqlbridge.common.work.SourcePartition;

public interface SourceConnector {

    RecordCursor open();

    default RecordCursor open(SourcePartition partition) {
        throw new UnsupportedOperationException("Source connector does not support partitioned reads.");
    }
}
