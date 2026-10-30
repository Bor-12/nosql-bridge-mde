package dev.borja.nosqlbridge.common.source.partitioning;

import java.util.Iterator;

import dev.borja.nosqlbridge.common.work.SourcePartition;

public interface SourcePartitionCursor extends Iterator<SourcePartition>, AutoCloseable {

    @Override
    void close();
}
