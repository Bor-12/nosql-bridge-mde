package dev.borja.nosqlbridge.runtime.source.connector;

import dev.borja.nosqlbridge.runtime.work.SourcePartition;

public interface SourceConnector {

    /** Opens a cursor that the caller must close, preferably with try-with-resources. */
    RecordCursor open();

    /** Opens only the supplied scope. The caller must close the returned cursor. */
    default RecordCursor open(SourcePartition partition) {
        throw new UnsupportedOperationException("Source connector does not support partitioned reads.");
    }
}
