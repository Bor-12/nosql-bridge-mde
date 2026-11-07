package dev.borja.nosqlbridge.worker;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import dev.borja.nosqlbridge.common.plan.BridgeExecutionPlan;
import dev.borja.nosqlbridge.common.plan.ConnectorPlan;
import dev.borja.nosqlbridge.common.queue.WorkQueue;
import dev.borja.nosqlbridge.common.work.*;
import dev.borja.nosqlbridge.worker.execution.WorkUnitExecutor;
import dev.borja.nosqlbridge.worker.work.WorkUnitResult;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerLoggingTest {
    private final Logger consumerLogger = (Logger) LoggerFactory.getLogger(WorkerConsumer.class);
    private final Logger ackLogger = (Logger) LoggerFactory.getLogger("dev.borja.nosqlbridge.worker.acknowledgement");
    private final ListAppender<ILoggingEvent> events = new ListAppender<>() {
        @Override protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };
    private Level consumerLevel;
    private Level ackLevel;
    private Map<String, String> previous;
    private final WorkQueue queue = mock(WorkQueue.class);
    private final WorkUnitExecutor executor = mock(WorkUnitExecutor.class);
    private final WorkUnit unit = new WorkUnit(new MigrationId(UUID.randomUUID()), new WorkUnitId(7),
            new SourcePartition("test", Map.of()));
    private final BridgeExecutionPlan plan = new BridgeExecutionPlan(1, "test",
            new ConnectorPlan("mongodb", Map.of()), new ConnectorPlan("dynamodb", Map.of()), "drop", "omit", List.of());
    private final WorkQueue.Delivery delivery = new WorkQueue.Delivery(new WorkUnitMessage(unit, plan), "secret-receipt");

    @BeforeEach void capture() {
        previous = MDC.getCopyOfContextMap();
        MDC.clear();
        consumerLevel = consumerLogger.getLevel();
        ackLevel = ackLogger.getLevel();
        consumerLogger.setLevel(Level.DEBUG);
        ackLogger.setLevel(Level.INFO);
        events.start();
        consumerLogger.addAppender(events);
        ackLogger.addAppender(events);
    }

    @AfterEach void restore() {
        consumerLogger.detachAppender(events);
        ackLogger.detachAppender(events);
        consumerLogger.setLevel(consumerLevel);
        ackLogger.setLevel(ackLevel);
        events.stop();
        if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
    }

    @Test void logsAckOnlyAfterSuccessWithTheExactDockerScriptContract() {
        when(queue.receive()).thenReturn(Optional.of(delivery));
        when(executor.execute(unit, plan)).thenReturn(new WorkUnitResult(unit.migrationId(), unit.workUnitId(), 3, 2));
        doAnswer(invocation -> {
            assertFalse(events.list.stream().anyMatch(e -> e.getFormattedMessage().startsWith("BRIDGE_WORKUNIT_ACKNOWLEDGED")));
            return null;
        }).when(queue).acknowledge(delivery);
        MDC.put("parent", "retained");
        new WorkerConsumer(queue, executor).processOne();
        ILoggingEvent ack = events.list.stream().filter(e -> e.getFormattedMessage().startsWith("BRIDGE_WORKUNIT_ACKNOWLEDGED")).findFirst().orElseThrow();
        assertTrue(Pattern.compile("BRIDGE_WORKUNIT_ACKNOWLEDGED migrationId=[0-9a-fA-F-]{36} workUnitId=7 recordsRead=3 recordsWritten=2 durationMs=\\d+")
                .matcher(ack.getFormattedMessage()).find());
        assertEquals(unit.migrationId().value().toString(), ack.getMDCPropertyMap().get("migrationId"));
        assertEquals(Map.of("parent", "retained"), MDC.getCopyOfContextMap());
    }

    @Test void failedAckIsLoggedOnceWithSafeErrorStackAndOriginalExceptionPropagation() {
        when(queue.receive()).thenReturn(Optional.of(delivery));
        when(executor.execute(unit, plan)).thenReturn(new WorkUnitResult(unit.migrationId(), unit.workUnitId(), 3, 3));
        IllegalStateException failure = new IllegalStateException("secret-sqs-payload");
        doThrow(failure).when(queue).acknowledge(delivery);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> new WorkerConsumer(queue, executor).processOne()));
        List<ILoggingEvent> errors = events.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().getFormattedMessage().contains("phase=acknowledge"));
        assertNotNull(errors.getFirst().getThrowableProxy());
        assertFalse(ThrowableProxyUtil.asString(errors.getFirst().getThrowableProxy()).contains("secret-sqs-payload"));
        assertFalse(events.list.stream().anyMatch(e -> e.getFormattedMessage().startsWith("BRIDGE_WORKUNIT_ACKNOWLEDGED")));
        assertNull(MDC.get("migrationId"));
    }

    @Test void receiveFailuresAreLoggedOnceByConsumerAndEmptyPollingIsSilent() {
        when(queue.receive()).thenThrow(new IllegalStateException("secret-endpoint")).thenReturn(Optional.empty());
        var time = new java.util.concurrent.atomic.AtomicLong();
        WorkerPollingTask polling = new WorkerPollingTask(new WorkerConsumer(queue, executor),
                new dev.borja.nosqlbridge.worker.config.WorkerBackoffProperties(null, null, 0.0, null),
                time::get, () -> 0.5);
        polling.poll();
        int loggedFailureEvents = events.list.size();
        polling.poll();
        verify(queue, times(1)).receive(); // Still in backoff: no second receive yet.
        time.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(1));
        polling.poll(); // Now actually receives an empty queue.
        polling.poll(); // A normal empty poll must also stay silent.
        verify(queue, times(3)).receive();
        verifyNoMoreInteractions(queue);
        verifyNoInteractions(executor);
        assertEquals(loggedFailureEvents, events.list.size(), "Empty receives must not emit consumer logs");
        assertEquals(1, events.list.stream().filter(e -> e.getLevel() == Level.WARN).count());
        ILoggingEvent error = events.list.stream().filter(e -> e.getLevel() == Level.WARN).findFirst().orElseThrow();
        assertTrue(error.getFormattedMessage().contains("phase=receive"));
        assertFalse(ThrowableProxyUtil.asString(error.getThrowableProxy()).contains("secret-endpoint"));
        assertEquals(0, events.list.stream().filter(e -> e.getLevel() == Level.ERROR).count());
    }
}
