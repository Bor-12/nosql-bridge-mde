package dev.borja.nosqlbridge.runtime.source.partitioning;

import java.util.Iterator;

import dev.borja.nosqlbridge.runtime.work.SourcePartition;

/**
 * Incremental partition reader owned by the caller; use try-with-resources.
 * Exhaustion closes resources, hasNext returns false and next throws
 * NoSuchElementException. Reading after an early close is not allowed.
 */
public interface SourcePartitionCursor extends Iterator<SourcePartition>, AutoCloseable {

    @Override
    void close();
}
