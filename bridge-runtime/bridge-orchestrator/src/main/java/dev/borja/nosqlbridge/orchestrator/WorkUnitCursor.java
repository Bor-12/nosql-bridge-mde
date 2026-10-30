package dev.borja.nosqlbridge.orchestrator;

import java.util.Iterator;

import dev.borja.nosqlbridge.common.work.WorkUnit;

public interface WorkUnitCursor extends Iterator<WorkUnit>, AutoCloseable {

    @Override
    void close();
}
