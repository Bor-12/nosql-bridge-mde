package dev.borja.nosqlbridge.runtime.orchestration;

import java.util.Iterator;

import dev.borja.nosqlbridge.runtime.work.WorkUnit;

public interface WorkUnitCursor extends Iterator<WorkUnit>, AutoCloseable {

    @Override
    void close();
}
