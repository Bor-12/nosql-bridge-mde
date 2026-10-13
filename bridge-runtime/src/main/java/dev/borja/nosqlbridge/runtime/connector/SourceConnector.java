package dev.borja.nosqlbridge.runtime.connector;

public interface SourceConnector {

    /** Opens a cursor that the caller must close, preferably with try-with-resources. */
    RecordCursor open();
}
