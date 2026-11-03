package dev.borja.nosqlbridge.orchestrator;

import java.util.UUID;

import dev.borja.nosqlbridge.common.plan.BridgeExecutionPlan;
import dev.borja.nosqlbridge.common.plan.PlanValidator;
import dev.borja.nosqlbridge.common.queue.WorkQueue;
import dev.borja.nosqlbridge.common.work.MigrationId;
import dev.borja.nosqlbridge.common.work.WorkUnitMessage;

public class MigrationPublisher {

    private final PlanValidator planValidator;
    private final MigrationPartitioner partitioner;
    private final WorkQueue queue;
    private final int recordsPerPartition;

    public MigrationPublisher(PlanValidator planValidator, MigrationPartitioner partitioner,
            WorkQueue queue, int recordsPerPartition) {
        if (planValidator == null || partitioner == null || queue == null) {
            throw new IllegalArgumentException("Plan validator, migration partitioner and work queue must not be null.");
        }
        if (recordsPerPartition <= 0) {
            throw new IllegalArgumentException("bridge.orchestrator.records-per-partition must be positive.");
        }
        this.planValidator = planValidator;
        this.partitioner = partitioner;
        this.queue = queue;
        this.recordsPerPartition = recordsPerPartition;
    }

    public Result publish(BridgeExecutionPlan plan) {
        planValidator.validate(plan);
        if (plan.mappings() == null) {
            throw new IllegalArgumentException("Execution plan mappings must not be null.");
        }
        MigrationId migrationId = new MigrationId(UUID.randomUUID());
        long publishedMessages = 0;
        try (WorkUnitCursor units = partitioner.open(plan, migrationId, recordsPerPartition)) {
            while (units.hasNext()) {
                queue.publish(new WorkUnitMessage(units.next(), plan));
                publishedMessages++;
            }
        } catch (RuntimeException failure) {
            throw new PublicationException(new Result(migrationId, publishedMessages), failure);
        }
        return new Result(migrationId, publishedMessages);
    }

    public record Result(MigrationId migrationId, long publishedMessages) {}

    public static final class PublicationException extends RuntimeException {

        private final Result progress;

        public PublicationException(Result progress, RuntimeException cause) {
            super("Migration " + progress.migrationId().value() + " failed after "
                    + progress.publishedMessages() + " publish calls completed successfully ("
                    + cause.getClass().getSimpleName() + "). Delivery of a failed operation may be uncertain.", cause);
            this.progress = progress;
        }

        public Result progress() {
            return progress;
        }
    }
}
