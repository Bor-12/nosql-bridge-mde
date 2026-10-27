package dev.borja.nosqlbridge.runtime.source.partitioning;

public interface SourcePartitioner {

    SourcePartitionCursor open(int recordsPerPartition);
}
