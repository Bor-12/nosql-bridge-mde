package dev.borja.nosqlbridge.runtime.source.partitioning;

import java.util.Iterator;

import dev.borja.nosqlbridge.runtime.work.SourcePartition;

public interface SourcePartitionCursor extends Iterator<SourcePartition>, AutoCloseable {

    @Override
    void close();
}
