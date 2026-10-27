package dev.borja.nosqlbridge.runtime.orchestration;

import java.util.NoSuchElementException;

import dev.borja.nosqlbridge.runtime.plan.BridgeExecutionPlan;
import dev.borja.nosqlbridge.runtime.registry.TechnologyRegistry;
import dev.borja.nosqlbridge.runtime.source.partitioning.SourcePartitionCursor;
import dev.borja.nosqlbridge.runtime.source.partitioning.SourcePartitioner;
import dev.borja.nosqlbridge.runtime.work.MigrationId;
import dev.borja.nosqlbridge.runtime.work.WorkUnit;
import dev.borja.nosqlbridge.runtime.work.WorkUnitId;

public class MigrationPartitioner {

    private final TechnologyRegistry technologyRegistry;

    public MigrationPartitioner(TechnologyRegistry technologyRegistry) {
        if (technologyRegistry == null) {
            throw new IllegalArgumentException("Technology registry must not be null.");
        }
        this.technologyRegistry = technologyRegistry;
    }

    public WorkUnitCursor open(BridgeExecutionPlan plan, MigrationId migrationId, int recordsPerPartition) {
        if (plan == null || plan.source() == null) {
            throw new IllegalArgumentException("Execution plan and source must not be null.");
        }
        if (migrationId == null) {
            throw new IllegalArgumentException("Migration id must not be null.");
        }
        if (recordsPerPartition <= 0) {
            throw new IllegalArgumentException("Records per partition must be positive.");
        }
        SourcePartitioner source = technologyRegistry.sourceFactory(plan.source().technology())
                .createPartitioner(plan.source());
        return new MigrationCursor(source.open(recordsPerPartition), migrationId);
    }

    private static final class MigrationCursor implements WorkUnitCursor {

        private final SourcePartitionCursor partitions;
        private final MigrationId migrationId;
        private long nextId;
        private boolean exhausted;
        private boolean closed;

        private MigrationCursor(SourcePartitionCursor partitions, MigrationId migrationId) {
            this.partitions = partitions;
            this.migrationId = migrationId;
        }

        @Override
        public boolean hasNext() {
            if (exhausted) {
                return false;
            }
            if (closed) {
                throw new IllegalStateException("Work unit cursor is closed.");
            }
            try {
                if (partitions.hasNext()) {
                    return true;
                }
                exhausted = true;
                close();
                return false;
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(failure);
                throw failure;
            }
        }

        @Override
        public WorkUnit next() {
            if (!hasNext()) {
                throw new NoSuchElementException("No more work units.");
            }
            try {
                return new WorkUnit(migrationId, new WorkUnitId(nextId++), partitions.next());
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(failure);
                throw failure;
            }
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                partitions.close();
            }
        }

        private void closeAfterFailure(Throwable failure) {
            try {
                close();
            } catch (RuntimeException | Error closeFailure) {
                if (closeFailure != failure) {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
    }
}
