package dev.borja.nosqlbridge.worker;

import java.util.Optional;
import java.util.Map;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;

import dev.borja.nosqlbridge.worker.execution.WorkUnitExecutor;
import dev.borja.nosqlbridge.common.queue.WorkQueue;
import dev.borja.nosqlbridge.common.queue.WorkQueue.Delivery;
import dev.borja.nosqlbridge.common.work.WorkUnitMessage;
import dev.borja.nosqlbridge.common.work.ExecutionEvents;
import dev.borja.nosqlbridge.worker.work.WorkUnitResult;
import dev.borja.nosqlbridge.common.logging.SafeLogException;

public class WorkerConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerConsumer.class);
    private static final Logger ACK_LOGGER = LoggerFactory.getLogger("dev.borja.nosqlbridge.worker.acknowledgement");

    private final WorkQueue workQueue;
    private final WorkUnitExecutor workUnitExecutor;
    private volatile boolean stopping;

    public WorkerConsumer(WorkQueue workQueue, WorkUnitExecutor workUnitExecutor) {
        if (workQueue == null) {
            throw new IllegalArgumentException("Work queue must not be null.");
        }
        if (workUnitExecutor == null) {
            throw new IllegalArgumentException("Work unit executor must not be null.");
        }
        this.workQueue = workQueue;
        this.workUnitExecutor = workUnitExecutor;
        LOGGER.atInfo().addKeyValue("eventName", "WORKER_ACK_LOGGING_READY")
                .log("BRIDGE_WORKUNIT_ACK_LOGGING_READY schemaVersion=2");
    }

    public Optional<WorkUnitResult> processOne() {
        return processOne(failure -> logFailure(failure, Level.ERROR, true));
    }

    // The scheduler observes a failure while its correlation context is still present.
    Optional<WorkUnitResult> processOne(Consumer<RuntimeException> failureHandler) {
        if (stopping) {
            return Optional.empty();
        }
        Map<String, String> previousContext = MDC.getCopyOfContextMap();
        long started = System.nanoTime();
        try {
            for (String key : new String[] {"migrationId", "workUnitId", "sourceField", "targetField", "transformation",
                    "recordsReadSoFar", "recordsWrittenConfirmedSoFar", "sourceTechnology", "targetTechnology",
                    "failureReason", "retryCount", "unprocessedItems", "failureDisposition",
                    "consecutiveFailures", "nextDelayMs", "failureDurationMs", "decisionReason"}) MDC.remove(key);
            MDC.put("workerId", System.getenv().getOrDefault("HOSTNAME", "local"));
            MDC.put("phase", "interruption_check");
            checkInterrupted();
            MDC.put("phase", "receive");
            Optional<Delivery> received = workQueue.receive();
            if (received.isEmpty() || stopping) return Optional.empty();
            MDC.put("phase", "interruption_check");
            checkInterrupted();
            Delivery delivery = received.orElseThrow();
            WorkUnitMessage message = delivery.message();
            var migrationId = message.workUnit().migrationId().value();
            long workUnitId = message.workUnit().workUnitId().value();
            MDC.put("migrationId", migrationId.toString());
            MDC.put("workUnitId", Long.toString(workUnitId));
            if (message.plan().source() != null) MDC.put("sourceTechnology", message.plan().source().technology());
            if (message.plan().target() != null) MDC.put("targetTechnology", message.plan().target().technology());
            MDC.put("phase", "execute");
            // Keep the existing duration definition: execution plus ACK, excluding receive.
            started = System.nanoTime();
            LOGGER.atDebug().addKeyValue("eventName", "WORKUNIT_STARTED")
                    .log("WORKUNIT_STARTED migrationId={} workUnitId={}", migrationId, workUnitId);
            ExecutionEvents.emit("PROCESSING", migrationId.toString(), Map.of("workUnitId", workUnitId));
            WorkUnitResult result;
            WorkQueue.Lease lease = workQueue.maintainVisibility(delivery);
            try (lease) {
                if (lease != null) lease.check();
                result = workUnitExecutor.execute(message.workUnit(), message.plan());
            }
            // Renewal is now stopped and any in-flight failure is visible;
            // no background renewal can race with this check or the ACK.
            if (lease != null) lease.check();
            MDC.put("phase", "interruption_check");
            checkInterrupted();
            MDC.put("phase", "acknowledge");
            workQueue.acknowledge(delivery);
            ExecutionEvents.emit("ACKNOWLEDGED", migrationId.toString(), Map.of("workUnitId", workUnitId,
                    "recordsRead", result.recordsRead(), "recordsWritten", result.recordsWritten()));
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            ACK_LOGGER.atInfo().addKeyValue("eventName", "WORKUNIT_ACKNOWLEDGED")
                    .addKeyValue("recordsRead", result.recordsRead()).addKeyValue("recordsWritten", result.recordsWritten())
                    .addKeyValue("durationMs", durationMs)
                    .log("BRIDGE_WORKUNIT_ACKNOWLEDGED migrationId={} workUnitId={} recordsRead={} recordsWritten={} durationMs={}",
                            migrationId, workUnitId, result.recordsRead(), result.recordsWritten(), durationMs);
            return Optional.of(result);
        } catch (RuntimeException failure) {
            MDC.put("failureDurationMs", Long.toString((System.nanoTime() - started) / 1_000_000));
            failureHandler.accept(failure);
            throw failure;
        } finally {
            if (previousContext == null) MDC.clear();
            else MDC.setContextMap(previousContext);
        }
    }

    void logFailure(RuntimeException failure, Level level, boolean includeStack) {
        String marker = MDC.get("workUnitId") == null ? "BRIDGE_WORKER_ITERATION_FAILED" : "BRIDGE_WORKUNIT_FAILED";
        var event = LOGGER.atLevel(level)
                .addKeyValue("eventName", MDC.get("workUnitId") == null ? "WORKER_ITERATION_FAILED" : "WORKUNIT_FAILED")
                .addKeyValue("errorType", failure.getClass().getName());
        String duration = MDC.get("failureDurationMs");
        MDC.remove("failureDurationMs");
        if (duration != null) event.addKeyValue("durationMs", Long.parseLong(duration));
        if (includeStack) event.setCause(SafeLogException.snapshot(failure));
        event.log("{} migrationId={} workUnitId={} phase={} errorType={} sourceField={} targetField={} transformation={} failureReason={} retryCount={} unprocessedItems={} failureDisposition={} decisionReason={} consecutiveFailures={} nextDelayMs={}",
                marker, MDC.get("migrationId"), MDC.get("workUnitId"), MDC.get("phase"), failure.getClass().getName(),
                MDC.get("sourceField"), MDC.get("targetField"), MDC.get("transformation"), MDC.get("failureReason"),
                MDC.get("retryCount"), MDC.get("unprocessedItems"), MDC.get("failureDisposition"), MDC.get("decisionReason"),
                MDC.get("consecutiveFailures"), MDC.get("nextDelayMs"));
    }

    public void stopReceiving() {
        stopping = true;
    }

    private void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Worker processing interrupted.");
        }
    }
}
