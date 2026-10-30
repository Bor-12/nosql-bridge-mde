package dev.borja.nosqlbridge.common.source.partitioning;

public interface SourcePartitioner {

    SourcePartitionCursor open(int recordsPerPartition);
}
