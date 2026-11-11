package dev.borja.nosqlbridge.orchestrator;

import java.util.UUID;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import dev.borja.nosqlbridge.common.plan.BridgeExecutionPlan;
import dev.borja.nosqlbridge.common.plan.PlanValidator;
import dev.borja.nosqlbridge.common.queue.WorkQueue;
import dev.borja.nosqlbridge.common.work.MigrationId;
import dev.borja.nosqlbridge.common.work.WorkUnitMessage;
import dev.borja.nosqlbridge.common.work.WorkUnit;
import dev.borja.nosqlbridge.common.work.ExecutionEvents;
import dev.borja.nosqlbridge.common.logging.SafeLogException;

public class MigrationPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationPublisher.class);
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
        long generatedUnits = 0;
        long partitionNanos = 0;
        long publicationNanos = 0;
        long started = System.nanoTime();
        long operationStarted = started;
        String phase = "partition_open";
        WorkUnit current = null;
        Map<String, String> previousContext = MDC.getCopyOfContextMap();
        try {
            ExecutionEvents.emit("STARTED", migrationId.value().toString(), Map.of());
            MDC.put("migrationId", migrationId.value().toString());
            MDC.remove("workUnitId");
            MDC.remove("phase");
            LOGGER.atInfo().addKeyValue("eventName", "MIGRATION_STARTED")
                    .addKeyValue("sourceTechnology", plan.source().technology())
                    .addKeyValue("targetTechnology", plan.target().technology())
                    .addKeyValue("recordsPerPartition", recordsPerPartition)
                    .log("MIGRATION_STARTED migrationId={} sourceTechnology={} targetTechnology={} recordsPerPartition={}",
                            migrationId.value(), plan.source().technology(), plan.target().technology(), recordsPerPartition);
            LOGGER.atInfo().addKeyValue("eventName", "PARTITIONING_STARTED")
                    .addKeyValue("mode", "incremental").addKeyValue("recordsPerPartition", recordsPerPartition)
                    .log("PARTITIONING_STARTED migrationId={}; generation and publication are interleaved.", migrationId.value());
            operationStarted = System.nanoTime();
            try (WorkUnitCursor units = partitioner.open(plan, migrationId, recordsPerPartition)) {
                partitionNanos += System.nanoTime() - operationStarted;
                while (true) {
                    phase = "partition";
                    current = null;
                    MDC.remove("workUnitId");
                    operationStarted = System.nanoTime();
                    boolean hasNext = units.hasNext();
                    partitionNanos += System.nanoTime() - operationStarted;
                    if (!hasNext) break;
                    operationStarted = System.nanoTime();
                    current = units.next();
                    partitionNanos += System.nanoTime() - operationStarted;
                    generatedUnits++;
                    phase = "publish";
                    MDC.put("workUnitId", Long.toString(current.workUnitId().value()));
                    if (publishedMessages == 0) {
                        LOGGER.atInfo().addKeyValue("eventName", "WORKUNIT_PUBLICATION_STARTED")
                                .log("WORKUNIT_PUBLICATION_STARTED migrationId={}", migrationId.value());
                    }
                    operationStarted = System.nanoTime();
                    queue.publish(new WorkUnitMessage(current, plan));
                    publicationNanos += System.nanoTime() - operationStarted;
                    publishedMessages++;
                    LOGGER.atDebug().addKeyValue("eventName", "WORKUNIT_PUBLISHED")
                            .log("WORKUNIT_PUBLISHED migrationId={} workUnitId={}", migrationId.value(), current.workUnitId().value());
                }
                phase = "partition_close";
                operationStarted = System.nanoTime();
            }
            partitionNanos += System.nanoTime() - operationStarted;
            LOGGER.atInfo().addKeyValue("eventName", "PARTITIONING_COMPLETED")
                    .addKeyValue("workUnitsGenerated", generatedUnits).addKeyValue("durationMs", partitionNanos / 1_000_000)
                    .log("PARTITIONING_COMPLETED migrationId={} workUnitsGenerated={} durationMs={}",
                            migrationId.value(), generatedUnits, partitionNanos / 1_000_000);
            LOGGER.atInfo().addKeyValue("eventName", "WORKUNITS_PUBLISHED")
                    .addKeyValue("publishedWorkUnits", publishedMessages).addKeyValue("durationMs", publicationNanos / 1_000_000)
                    .addKeyValue("totalDurationMs", (System.nanoTime() - started) / 1_000_000)
                    .log("WORKUNITS_PUBLISHED migrationId={} publishedWorkUnits={} durationMs={}; consumers may still be processing.",
                            migrationId.value(), publishedMessages, publicationNanos / 1_000_000);
            ExecutionEvents.emit("PUBLISHED", migrationId.value().toString(), Map.of("publishedWorkUnits", publishedMessages));
        } catch (RuntimeException failure) {
            ExecutionEvents.emit("FAILED", migrationId.value().toString(), Map.of("publishedWorkUnits", publishedMessages));
            LOGGER.atError().addKeyValue("eventName", "MIGRATION_PUBLICATION_FAILED")
                    .addKeyValue("phase", phase).addKeyValue("publishedWorkUnits", publishedMessages)
                    .addKeyValue("workUnitsGenerated", generatedUnits).addKeyValue("errorType", failure.getClass().getName())
                    .addKeyValue("durationMs", (System.nanoTime() - started) / 1_000_000)
                    .setCause(SafeLogException.snapshot(failure))
                    .log("MIGRATION_PUBLICATION_FAILED migrationId={} workUnitId={} phase={} publishedWorkUnits={} errorType={}",
                            migrationId.value(), current == null ? null : current.workUnitId().value(), phase,
                            publishedMessages, failure.getClass().getName());
            throw new PublicationException(new Result(migrationId, publishedMessages), failure);
        } finally {
            if (previousContext == null) MDC.clear();
            else MDC.setContextMap(previousContext);
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
