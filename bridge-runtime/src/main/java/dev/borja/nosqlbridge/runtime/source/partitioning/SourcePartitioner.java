package dev.borja.nosqlbridge.runtime.source.partitioning;

/** Discovers source partitions incrementally without retaining the full plan. */
public interface SourcePartitioner {

    /** Opens a cursor without consuming source ids. The size must be positive. */
    SourcePartitionCursor open(int recordsPerPartition);
}
