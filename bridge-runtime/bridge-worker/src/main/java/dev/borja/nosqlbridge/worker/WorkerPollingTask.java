package dev.borja.nosqlbridge.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.Map;
import dev.borja.nosqlbridge.common.work.ExecutionEvents;
import dev.borja.nosqlbridge.worker.config.WorkerBackoffProperties;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;

public class WorkerPollingTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerPollingTask.class);

    private final WorkerConsumer consumer;
    private final WorkerBackoffProperties backoff;
    private final LongSupplier nanoTime;
    private final DoubleSupplier random;
    private final Runnable fatalShutdown;
    private final AtomicBoolean polling = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private int consecutiveFailures;
    private long nextAllowedNanos;
    private long lastStackNanos;
    private String lastStackSignature;
    private String lastFailurePhase;
    private boolean failureHandled;

    public WorkerPollingTask(WorkerConsumer consumer) {
        this(consumer, new WorkerBackoffProperties(null, null, null, null));
    }

    public WorkerPollingTask(WorkerConsumer consumer, WorkerBackoffProperties backoff) {
        this(consumer, backoff, () -> {});
    }

    public WorkerPollingTask(WorkerConsumer consumer, WorkerBackoffProperties backoff, Runnable fatalShutdown) {
        this(consumer, backoff, System::nanoTime, () -> ThreadLocalRandom.current().nextDouble(), fatalShutdown);
    }

    WorkerPollingTask(WorkerConsumer consumer, WorkerBackoffProperties backoff, LongSupplier nanoTime, DoubleSupplier random) {
        this(consumer, backoff, nanoTime, random, () -> {});
    }

    WorkerPollingTask(WorkerConsumer consumer, WorkerBackoffProperties backoff, LongSupplier nanoTime, DoubleSupplier random,
            Runnable fatalShutdown) {
        if (consumer == null || backoff == null || nanoTime == null || random == null || fatalShutdown == null) {
            throw new IllegalArgumentException("Worker polling dependencies must not be null.");
        }
        this.consumer = consumer;
        this.backoff = backoff;
        this.nanoTime = nanoTime;
        this.random = random;
        this.fatalShutdown = fatalShutdown;
    }

    @Scheduled(fixedDelay = 1000, scheduler = "workerTaskScheduler")
    public void poll() {
        if (stopped.get() || Thread.currentThread().isInterrupted() || !polling.compareAndSet(false, true)) return;
        Map<String, String> previousContext = MDC.getCopyOfContextMap();
        try {
            if (stopped.get() || Thread.currentThread().isInterrupted()) return;
            if (consecutiveFailures > 0 && nanoTime.getAsLong() - nextAllowedNanos < 0) return;
            failureHandled = false;
            var result = consumer.processOne(this::handleFailure);
            // An empty receive proves SQS recovered, not that MongoDB/DynamoDB recovered.
            boolean recovered = result.isPresent() || "receive".equals(lastFailurePhase);
            if (consecutiveFailures > 0 && recovered) {
                LOGGER.atInfo().addKeyValue("eventName", "WORKER_POLLING_RECOVERED")
                        .addKeyValue("consecutiveFailures", consecutiveFailures).addKeyValue("lastFailurePhase", lastFailurePhase)
                        .log("WORKER_POLLING_RECOVERED consecutiveFailures={} lastFailurePhase={}",
                                consecutiveFailures, lastFailurePhase);
            }
            if (recovered) {
                consecutiveFailures = 0;
                nextAllowedNanos = 0;
                lastStackSignature = null;
                lastFailurePhase = null;
            }
        } catch (RuntimeException failure) {
            // Consumer normally calls the handler before restoring MDC. Keep a fallback for unexpected callers.
            if (!failureHandled) handleFailure(failure);
        } finally {
            if (previousContext == null) MDC.clear(); else MDC.setContextMap(previousContext);
            polling.set(false);
        }
    }

    private void handleFailure(RuntimeException failure) {
        failureHandled = true;
        var decision = WorkerFailureClassifier.decide(failure, MDC.get("phase"), MDC.get("workUnitId") != null);
        if (stopped.get() || Thread.currentThread().isInterrupted() || decision.action() == WorkerFailureClassifier.Action.INTERRUPTED) {
            if (decision.action() == WorkerFailureClassifier.Action.INTERRUPTED) Thread.currentThread().interrupt();
            stopped.set(true);
            consumer.stopReceiving();
            MDC.put("failureDisposition", "STOPPED");
            consumer.logFailure(failure, Level.DEBUG, false);
            return;
        }
        // This is explicit connector metadata, not a guess based on exception message text.
        if (decision.action() == WorkerFailureClassifier.Action.WORKUNIT_FAILED
                && "unprocessed_items_retries_exhausted".equals(MDC.get("failureReason"))) {
            decision = new WorkerFailureClassifier.Decision(WorkerFailureClassifier.Action.RETRYABLE, "dynamodb_unprocessed_items_retries_exhausted");
        }
        long now = nanoTime.getAsLong();
        String phase = MDC.get("phase");
        String signature = decision.action() + ":" + phase + ":" + failure.getClass().getName();
        boolean includeStack = !signature.equals(lastStackSignature)
                || now - lastStackNanos >= backoff.stackTraceInterval().toNanos();
        if (includeStack) {
            lastStackSignature = signature;
            lastStackNanos = now;
        }
        MDC.put("decisionReason", decision.reason());
        if (decision.action() == WorkerFailureClassifier.Action.RETRYABLE) {
            if (consecutiveFailures < Integer.MAX_VALUE) consecutiveFailures++;
            lastFailurePhase = phase;
            MDC.put("consecutiveFailures", Integer.toString(consecutiveFailures));
            long delayMs = backoff.delayMillis(consecutiveFailures, random.getAsDouble());
            nextAllowedNanos = now + delayMs * 1_000_000;
            MDC.put("failureDisposition", "RETRYABLE");
            MDC.put("nextDelayMs", Long.toString(delayMs));
            consumer.logFailure(failure, Level.WARN, includeStack);
        } else if (decision.action() == WorkerFailureClassifier.Action.FATAL) {
            emitFailure();
            stopped.set(true);
            consumer.stopReceiving();
            MDC.put("failureDisposition", "FATAL");
            consumer.logFailure(failure, Level.ERROR, includeStack);
            fatalShutdown.run();
        } else {
            emitFailure();
            // Leave this delivery to SQS visibility/redrive; another unit may still be valid.
            consecutiveFailures = 0;
            nextAllowedNanos = 0;
            lastFailurePhase = null;
            MDC.put("failureDisposition", "WORKUNIT_FAILED_REVIEW");
            consumer.logFailure(failure, Level.ERROR, includeStack);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        if (stopped.get()) return;
        ExecutionEvents.emit("READY", null, Map.of());
        LOGGER.atInfo().addKeyValue("eventName", "WORKER_READY")
                .log("WORKER_READY workerId={}", System.getenv().getOrDefault("HOSTNAME", "local"));
    }

    private static void emitFailure() {
        String migration = MDC.get("migrationId");
        if (migration != null) ExecutionEvents.emit("FAILED", migration, Map.of());
    }

    @EventListener(ContextClosedEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void stop() {
        stopped.set(true);
        consumer.stopReceiving();
        LOGGER.atInfo().addKeyValue("eventName", "WORKER_STOPPING")
                .log("WORKER_STOPPING: no further messages will be received; in-flight work follows existing shutdown behavior.");
    }
}
