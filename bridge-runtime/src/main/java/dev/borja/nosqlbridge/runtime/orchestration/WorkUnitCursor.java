package dev.borja.nosqlbridge.runtime.orchestration;

import java.util.Iterator;

import dev.borja.nosqlbridge.runtime.work.WorkUnit;

/**
 * Incremental work reader owned by the caller; use try-with-resources.
 * Exhaustion closes resources, hasNext returns false and next throws
 * NoSuchElementException. Reading after an early close is not allowed.
 */
public interface WorkUnitCursor extends Iterator<WorkUnit>, AutoCloseable {

    @Override
    void close();
}
