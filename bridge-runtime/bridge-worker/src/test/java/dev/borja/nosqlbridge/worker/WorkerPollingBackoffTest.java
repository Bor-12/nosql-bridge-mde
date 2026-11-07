package dev.borja.nosqlbridge.worker;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.model.SqsException;
import dev.borja.nosqlbridge.worker.config.WorkerBackoffProperties;
import dev.borja.nosqlbridge.worker.execution.WorkUnitExecutor;
import dev.borja.nosqlbridge.worker.work.WorkUnitResult;
import dev.borja.nosqlbridge.common.plan.*;
import dev.borja.nosqlbridge.common.queue.WorkQueue;
import dev.borja.nosqlbridge.common.work.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerPollingBackoffTest {
    private final WorkQueue queue = mock(WorkQueue.class);
    private final WorkUnitExecutor executor = mock(WorkUnitExecutor.class);
    private final AtomicLong time = new AtomicLong();
    private final Logger consumerLogger = (Logger) LoggerFactory.getLogger(WorkerConsumer.class);
    private final Logger pollingLogger = (Logger) LoggerFactory.getLogger(WorkerPollingTask.class);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>() {
        @Override protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };
    private Level consumerLevel;
    private Level pollingLevel;
    private Map<String, String> previousContext;

    @BeforeEach void capture() {
        previousContext = MDC.getCopyOfContextMap();
        MDC.clear();
        consumerLevel = consumerLogger.getLevel();
        pollingLevel = pollingLogger.getLevel();
        consumerLogger.setLevel(Level.DEBUG);
        pollingLogger.setLevel(Level.INFO);
        events.start();
        consumerLogger.addAppender(events);
        pollingLogger.addAppender(events);
    }

    @AfterEach void restore() {
        consumerLogger.detachAppender(events);
        pollingLogger.detachAppender(events);
        consumerLogger.setLevel(consumerLevel);
        pollingLogger.setLevel(pollingLevel);
        if (previousContext == null) MDC.clear(); else MDC.setContextMap(previousContext);
        events.stop();
    }

    private WorkerPollingTask task(double jitterRatio, double sample) {
        return new WorkerPollingTask(new WorkerConsumer(queue, executor),
                new WorkerBackoffProperties(null, null, jitterRatio, Duration.ofMinutes(1)), time::get, () -> sample);
    }

    private RuntimeException transientFailure() {
        return SdkClientException.create("private-payload", new SocketTimeoutException("private-address"));
    }

    private void advance(long millis) { time.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis)); }

    @Test void increasesDelayHonorsTheCapAndNeverCallsReceiveBeforeTheDeadline() {
        when(queue.receive()).thenThrow(transientFailure());
        WorkerPollingTask task = task(0, 0.5);
        task.poll();
        long[] delays = {1000, 2000, 4000, 8000, 16000, 30000, 30000};
        for (int index = 0; index < delays.length; index++) {
            verify(queue, times(index + 1)).receive();
            advance(delays[index] - 1);
            task.poll();
            verify(queue, times(index + 1)).receive();
            advance(1);
            task.poll();
        }
        verify(queue, times(delays.length + 1)).receive();
        verifyNoInteractions(executor);
    }

    @Test void appliesJitterAndResetsAfterSuccessfulEmptyReceiveWhenSqsWasFailing() {
        when(queue.receive()).thenThrow(transientFailure()).thenReturn(Optional.empty()).thenThrow(transientFailure());
        WorkerPollingTask task = task(0.5, 0);
        task.poll();
        advance(499);
        task.poll();
        verify(queue).receive();
        advance(1);
        task.poll();
        task.poll();
        verify(queue, times(3)).receive();
        ILoggingEvent lastFailure = events.list.stream().filter(e -> e.getFormattedMessage().startsWith("BRIDGE_WORKER_ITERATION_FAILED")).reduce((first, last) -> last).orElseThrow();
        assertEquals("1", lastFailure.getMDCPropertyMap().get("consecutiveFailures"));
        assertEquals("500", lastFailure.getMDCPropertyMap().get("nextDelayMs"));
        assertEquals(1, events.list.stream().filter(e -> e.getFormattedMessage().startsWith("WORKER_POLLING_RECOVERED")).count());
    }

    @Test void emptyQueueIsNotAFailureOrARecoveryWithoutAnIncident() {
        when(queue.receive()).thenReturn(Optional.empty());
        WorkerPollingTask task = task(0.5, 0);
        task.poll();
        task.poll();
        verify(queue, times(2)).receive();
        assertFalse(events.list.stream().anyMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN)));
        assertFalse(events.list.stream().anyMatch(e -> e.getFormattedMessage().startsWith("WORKER_POLLING_RECOVERED")));
    }

    @Test void fatalQueueConfigurationStopsTheInstanceExplicitlyAndInvokesShutdownOnlyOnce() {
        when(queue.receive()).thenThrow(SqsException.builder().statusCode(403).message("private-credentials").build());
        AtomicInteger shutdowns = new AtomicInteger();
        try (var instance = new org.springframework.context.support.GenericApplicationContext()) {
            instance.refresh();
            WorkerPollingTask task = new WorkerPollingTask(new WorkerConsumer(queue, executor),
                    new WorkerBackoffProperties(null, null, null, null), time::get, () -> 0.5,
                    () -> { shutdowns.incrementAndGet(); instance.close(); });
            task.poll();
            assertFalse(instance.isActive());
            for (int index = 0; index < 10; index++) {
                advance(60_000);
                task.poll();
            }
            assertEquals(1, shutdowns.get());
            verify(queue).receive();
            verifyNoMoreInteractions(queue);
        }
        assertTrue(events.list.stream().anyMatch(e -> "FATAL".equals(e.getMDCPropertyMap().get("failureDisposition"))));
        assertTrue(events.list.stream().anyMatch(e -> "configured_queue_access_or_request_rejected".equals(e.getMDCPropertyMap().get("decisionReason"))));
    }

    @Test void unclassifiedReceiveErrorBacksOffAndCanRecoverInsteadOfPausingForever() {
        when(queue.receive()).thenThrow(new IllegalStateException("private-unknown-failure")).thenReturn(Optional.empty());
        WorkerPollingTask task = task(0, 1);
        task.poll();
        task.poll();
        verify(queue).receive();
        advance(1000);
        task.poll();
        task.poll();
        verify(queue, times(3)).receive();
        assertTrue(events.list.stream().anyMatch(e -> "uncorrelated_failure_backoff_review".equals(e.getMDCPropertyMap().get("decisionReason"))));
        assertTrue(events.list.stream().anyMatch(e -> e.getFormattedMessage().startsWith("WORKER_POLLING_RECOVERED")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void badOrUnknownWorkUnitIsNotAcknowledgedAndTheNextWorkUnitStillCompletes(boolean unknown) {
        MigrationId migration = new MigrationId(UUID.randomUUID());
        WorkUnit bad = new WorkUnit(migration, new WorkUnitId(1), new SourcePartition("test", Map.of()));
        WorkUnit good = new WorkUnit(migration, new WorkUnitId(2), new SourcePartition("test", Map.of()));
        BridgeExecutionPlan plan = new BridgeExecutionPlan(1, "test", new ConnectorPlan("mongodb", Map.of()),
                new ConnectorPlan("dynamodb", Map.of()), "drop", "omit", List.of());
        var badDelivery = new WorkQueue.Delivery(new WorkUnitMessage(bad, plan), "private-bad-receipt");
        var goodDelivery = new WorkQueue.Delivery(new WorkUnitMessage(good, plan), "private-good-receipt");
        when(queue.receive()).thenReturn(Optional.of(badDelivery), Optional.of(goodDelivery));
        when(executor.execute(bad, plan)).thenAnswer(invocation -> {
            MDC.put("phase", "transform");
            throw unknown ? new IllegalStateException("private-data") : new NumberFormatException("private-data");
        });
        when(executor.execute(good, plan)).thenReturn(new WorkUnitResult(migration, good.workUnitId(), 1, 1));
        AtomicInteger shutdowns = new AtomicInteger();
        WorkerPollingTask task = new WorkerPollingTask(new WorkerConsumer(queue, executor),
                new WorkerBackoffProperties(null, null, null, null), time::get, () -> 1, shutdowns::incrementAndGet);
        task.poll();
        task.poll();
        verify(queue, times(2)).receive();
        verify(queue, never()).acknowledge(badDelivery);
        verify(queue).acknowledge(goodDelivery);
        verify(executor).execute(bad, plan);
        verify(executor).execute(good, plan);
        assertEquals(0, shutdowns.get());
        ILoggingEvent failure = events.list.stream().filter(e -> e.getFormattedMessage().startsWith("BRIDGE_WORKUNIT_FAILED")).findFirst().orElseThrow();
        assertEquals("WORKUNIT_FAILED_REVIEW", failure.getMDCPropertyMap().get("failureDisposition"));
        assertEquals("1", failure.getMDCPropertyMap().get("workUnitId"));
        assertEquals(migration.value().toString(), failure.getMDCPropertyMap().get("migrationId"));
    }

    @Test void failedWorkUnitIsNotAcknowledgedOrLocallyReplayedAndEmptySqsDoesNotProveDatabaseRecovery() {
        WorkUnit unit = new WorkUnit(new MigrationId(UUID.randomUUID()), new WorkUnitId(1), new SourcePartition("test", Map.of()));
        BridgeExecutionPlan plan = new BridgeExecutionPlan(1, "test", new ConnectorPlan("mongodb", Map.of()),
                new ConnectorPlan("dynamodb", Map.of()), "drop", "omit", List.of());
        WorkQueue.Delivery delivery = new WorkQueue.Delivery(new WorkUnitMessage(unit, plan), "private-receipt");
        when(queue.receive()).thenReturn(Optional.of(delivery), Optional.empty(), Optional.of(delivery));
        when(executor.execute(unit, plan)).thenAnswer(invocation -> {
            MDC.put("phase", "target_write");
            throw transientFailure();
        }).thenReturn(new WorkUnitResult(unit.migrationId(), unit.workUnitId(), 1, 1));
        WorkerPollingTask task = task(0, 1);
        task.poll();
        task.poll();
        verify(queue).receive();
        verify(executor).execute(unit, plan);
        verify(queue, never()).acknowledge(any());
        advance(1000);
        task.poll();
        assertFalse(events.list.stream().anyMatch(e -> e.getFormattedMessage().startsWith("WORKER_POLLING_RECOVERED")));
        // Only the queue supplies a new delivery; the worker never replays the old one itself.
        advance(120_000);
        task.poll();
        verify(queue, times(3)).receive();
        verify(executor, times(2)).execute(unit, plan);
        verify(queue).acknowledge(delivery);
        assertTrue(events.list.stream().anyMatch(e -> e.getFormattedMessage().startsWith("WORKER_POLLING_RECOVERED")));
    }

    @Test void logsOneSafeStackPerIncidentAndPeriodicDiagnosticsRatherThanEveryFailedPoll() {
        when(queue.receive()).thenThrow(transientFailure());
        WorkerPollingTask task = task(0, 1);
        MDC.put("caller", "retained");
        task.poll();
        advance(1000);
        task.poll();
        List<ILoggingEvent> failures = events.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertEquals(2, failures.size());
        assertNotNull(failures.getFirst().getThrowableProxy());
        assertNull(failures.getLast().getThrowableProxy());
        assertEquals(Map.of("caller", "retained"), MDC.getCopyOfContextMap());
        advance(60_000);
        task.poll();
        assertNotNull(events.list.getLast().getThrowableProxy());
    }

    @Test void stopDuringBackoffPreventsAnyFurtherRequest() {
        when(queue.receive()).thenThrow(transientFailure());
        WorkerPollingTask task = task(0, 1);
        task.poll();
        task.stop();
        advance(120_000);
        task.poll();
        verify(queue).receive();
        verifyNoMoreInteractions(queue);
    }

    @Test void respectsInterruptsWithoutApplyingBackoffOrClearingTheSignal() {
        when(queue.receive()).thenThrow(SdkClientException.create("private", new InterruptedException()));
        WorkerPollingTask task = task(0, 1);
        try {
            task.poll();
            assertTrue(Thread.currentThread().isInterrupted());
            task.poll();
            verify(queue).receive();
        } finally { Thread.interrupted(); }
    }

    @Test void preventsConcurrentPollingAndStoppingDoesNotWaitForTheActiveReceive() throws Exception {
        CountDownLatch receiving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(queue.receive()).thenAnswer(invocation -> {
            receiving.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            return Optional.empty();
        });
        WorkerPollingTask task = task(0, 1);
        Thread active = new Thread(task::poll);
        active.start();
        try {
            assertTrue(receiving.await(2, TimeUnit.SECONDS));
            task.poll();
            task.stop();
            verify(queue).receive();
        } finally {
            release.countDown();
            active.join(2000);
        }
        assertFalse(active.isAlive());
        task.poll();
        verify(queue).receive();
    }
}
