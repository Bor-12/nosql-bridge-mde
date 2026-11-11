package dev.borja.nosqlbridge.worker;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import dev.borja.nosqlbridge.worker.execution.WorkUnitExecutor;
import dev.borja.nosqlbridge.common.plan.BridgeExecutionPlan;
import dev.borja.nosqlbridge.common.plan.ConnectorPlan;
import dev.borja.nosqlbridge.common.queue.WorkQueue;
import dev.borja.nosqlbridge.common.queue.WorkQueue.Delivery;
import dev.borja.nosqlbridge.common.work.MigrationId;
import dev.borja.nosqlbridge.common.work.SourcePartition;
import dev.borja.nosqlbridge.common.work.WorkUnit;
import dev.borja.nosqlbridge.common.work.WorkUnitId;
import dev.borja.nosqlbridge.common.work.WorkUnitMessage;
import dev.borja.nosqlbridge.worker.work.WorkUnitResult;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class WorkerConsumerTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void reportsRenewalFailureThroughPollingWithoutEmittingAcknowledgement(boolean initialFailure, CapturedOutput output) {
        WorkQueue.Lease lease = mock(WorkQueue.Lease.class);
        when(workQueue.receive()).thenReturn(Optional.of(delivery));
        when(workQueue.maintainVisibility(delivery)).thenReturn(lease);
        if (initialFailure) {
            doThrow(new IllegalStateException("renewal failed")).when(lease).check();
        } else {
            org.mockito.Mockito.doNothing().doThrow(new IllegalStateException("renewal failed")).when(lease).check();
            when(workUnitExecutor.execute(unit, plan)).thenReturn(new WorkUnitResult(unit.migrationId(), unit.workUnitId(), 1, 1));
        }

        new WorkerPollingTask(consumer).poll();

        verify(lease).close();
        if (initialFailure) verifyNoInteractions(workUnitExecutor);
        else verify(workUnitExecutor).execute(unit, plan);
        verify(workQueue, org.mockito.Mockito.never()).acknowledge(delivery);
        assertTrue(output.getOut().contains("\"type\":\"PROCESSING\""));
        assertTrue(output.getOut().contains("\"type\":\"FAILED\""));
        org.junit.jupiter.api.Assertions.assertFalse(output.getOut().contains("\"type\":\"ACKNOWLEDGED\""));
    }

    @Test
    void refusesAcknowledgementWhenAnInFlightRenewalFailsDuringLeaseClose() {
        var failed = new java.util.concurrent.atomic.AtomicBoolean();
        WorkQueue.Lease lease = new WorkQueue.Lease() {
            public void check() {
                if (failed.get()) throw new IllegalStateException("renewal failed during close");
            }
            public void close() { failed.set(true); }
        };
        when(workQueue.receive()).thenReturn(Optional.of(delivery));
        when(workQueue.maintainVisibility(delivery)).thenReturn(lease);
        when(workUnitExecutor.execute(unit, plan)).thenReturn(new WorkUnitResult(unit.migrationId(), unit.workUnitId(), 1, 1));

        assertThrows(IllegalStateException.class, consumer::processOne);

        verify(workQueue, org.mockito.Mockito.never()).acknowledge(delivery);
    }

    @Test
    void refusesAcknowledgementWhenVisibilityRenewalFailsAfterExecutionAndClosesLease() {
        WorkQueue.Lease lease = mock(WorkQueue.Lease.class);
        when(workQueue.receive()).thenReturn(Optional.of(delivery));
        when(workQueue.maintainVisibility(delivery)).thenReturn(lease);
        when(workUnitExecutor.execute(unit, plan)).thenReturn(new WorkUnitResult(unit.migrationId(), unit.workUnitId(), 1, 1));
        org.mockito.Mockito.doNothing().doThrow(new IllegalStateException("renewal failed")).when(lease).check();
        assertThrows(IllegalStateException.class, consumer::processOne);
        verify(lease).close();
        verify(workQueue, org.mockito.Mockito.never()).acknowledge(org.mockito.ArgumentMatchers.any(Delivery.class));
    }

    @Mock
    private WorkQueue workQueue;
    @Mock
    private WorkUnitExecutor workUnitExecutor;

    private final WorkUnit unit = new WorkUnit(
            new MigrationId(UUID.fromString("550e8400-e29b-41d4-a716-446655440000")), new WorkUnitId(2),
            new SourcePartition("test-range", Map.of("upper", "10")));
    private final BridgeExecutionPlan plan = new BridgeExecutionPlan(1, "test",
            new ConnectorPlan("source", Map.of()), new ConnectorPlan("target", Map.of()),
            "drop", "omit", List.of());
    private final WorkUnitMessage message = new WorkUnitMessage(unit, plan);
    private final Delivery delivery = new Delivery(message, "receipt-first");
    private WorkerConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new WorkerConsumer(workQueue, workUnitExecutor);
    }

    @Test
    void stopsReceivingNewWorkAfterShutdownBegins() {
        consumer.stopReceiving();

        assertTrue(consumer.processOne().isEmpty());

        verifyNoInteractions(workQueue, workUnitExecutor);
    }

    @Test
    void leavesDeliveryUnprocessedWhenShutdownBeginsDuringReceive() {
        when(workQueue.receive()).thenAnswer(invocation -> {
            consumer.stopReceiving();
            return Optional.of(delivery);
        });

        assertTrue(consumer.processOne().isEmpty());

        verify(workQueue).receive();
        verifyNoMoreInteractions(workQueue);
        verifyNoInteractions(workUnitExecutor);
    }

    @Test
    void allowsAnActiveExecutionToFinishAndAcknowledgeDuringGracefulShutdown() {
        WorkUnitResult result = new WorkUnitResult(unit.migrationId(), unit.workUnitId(), 5, 5);
        when(workQueue.receive()).thenReturn(Optional.of(delivery));
        when(workUnitExecutor.execute(unit, plan)).thenAnswer(invocation -> {
            consumer.stopReceiving();
            return result;
        });

        assertSame(result, consumer.processOne().orElseThrow());

        verify(workQueue).acknowledge(delivery);
    }

    @ParameterizedTest
    @ValueSource(strings = {"beforeReceive", "afterReceive", "afterExecution"})
    void neverAcknowledgesInterruptedProcessing(String interruptionPoint) {
        WorkQueue.Lease lease = mock(WorkQueue.Lease.class);
        WorkUnitResult result = new WorkUnitResult(unit.migrationId(), unit.workUnitId(), 5, 5);
        if ("beforeReceive".equals(interruptionPoint)) {
            Thread.currentThread().interrupt();
        } else {
            when(workQueue.receive()).thenAnswer(invocation -> {
                if ("afterReceive".equals(interruptionPoint)) {
                    Thread.currentThread().interrupt();
                }
                return Optional.of(delivery);
            });
            if ("afterExecution".equals(interruptionPoint)) {
                when(workQueue.maintainVisibility(delivery)).thenReturn(lease);
                when(workUnitExecutor.execute(unit, plan)).thenAnswer(invocation -> {
                    Thread.currentThread().interrupt();
                    return result;
                });
            }
        }
        try {
            assertThrows(IllegalStateException.class, consumer::processOne);
            assertTrue(Thread.currentThread().isInterrupted());
            org.mockito.Mockito.verify(workQueue, org.mockito.Mockito.never())
                    .acknowledge(org.mockito.ArgumentMatchers.any(Delivery.class));
            if (!"afterExecution".equals(interruptionPoint)) {
                verifyNoInteractions(workUnitExecutor);
            } else {
                verify(lease).close();
            }
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void rejectsNullDependencies() {
        assertThrows(IllegalArgumentException.class, () -> new WorkerConsumer(null, workUnitExecutor));
        assertThrows(IllegalArgumentException.class, () -> new WorkerConsumer(workQueue, null));
        verifyNoInteractions(workQueue, workUnitExecutor);
    }

    @Test
    void returnsEmptyWithoutExecutionOrAcknowledgementWhenThereIsNoWork() {
        when(workQueue.receive()).thenReturn(Optional.empty());

        assertTrue(consumer.processOne().isEmpty());

        verify(workQueue).receive();
        verifyNoMoreInteractions(workQueue);
        verifyNoInteractions(workUnitExecutor);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 5})
    void acknowledgesExactlyOnceAfterExecutionAndReturnsTheResult(long count, CapturedOutput output) {
        WorkQueue.Lease lease = mock(WorkQueue.Lease.class);
        WorkUnitResult expected = new WorkUnitResult(unit.migrationId(), unit.workUnitId(), count, count);
        when(workQueue.receive()).thenReturn(Optional.of(delivery));
        when(workQueue.maintainVisibility(delivery)).thenReturn(lease);
        when(workUnitExecutor.execute(same(unit), same(plan))).thenReturn(expected);

        assertSame(expected, consumer.processOne().orElseThrow());

        InOrder order = inOrder(workQueue, workUnitExecutor, lease);
        order.verify(workQueue).receive();
        order.verify(workQueue).maintainVisibility(org.mockito.ArgumentMatchers.any(Delivery.class));
        order.verify(lease).check();
        order.verify(workUnitExecutor).execute(same(unit), same(plan));
        order.verify(lease).close();
        order.verify(lease).check();
        order.verify(workQueue).acknowledge(same(delivery));
        verifyNoMoreInteractions(workQueue, workUnitExecutor, lease);
        String events = output.getOut();
        assertTrue(events.indexOf("\"type\":\"PROCESSING\"") < events.indexOf("\"type\":\"ACKNOWLEDGED\""));
        assertTrue(events.contains("\"type\":\"ACKNOWLEDGED\""));
        org.junit.jupiter.api.Assertions.assertFalse(events.contains("\"type\":\"FAILED\""));
    }

    @Test
    void propagatesReceiveFailureWithoutExecutionOrAcknowledgement() {
        IllegalStateException failure = new IllegalStateException("Receive failed");
        when(workQueue.receive()).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, consumer::processOne));

        verify(workQueue).receive();
        verifyNoMoreInteractions(workQueue);
        verifyNoInteractions(workUnitExecutor);
    }

    @Test
    void propagatesExecutionFailureWithoutAcknowledgementOrRetry() {
        WorkQueue.Lease lease = mock(WorkQueue.Lease.class);
        IllegalArgumentException failure = new IllegalArgumentException("Execution failed");
        when(workQueue.receive()).thenReturn(Optional.of(delivery));
        when(workQueue.maintainVisibility(delivery)).thenReturn(lease);
        when(workUnitExecutor.execute(unit, plan)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalArgumentException.class, consumer::processOne));

        verify(workQueue).receive();
        verify(workQueue).maintainVisibility(delivery);
        verify(workUnitExecutor).execute(same(unit), same(plan));
        verify(lease).close();
        verifyNoMoreInteractions(workQueue, workUnitExecutor);
    }

    @Test
    void propagatesAcknowledgementFailureWithoutRepeatingExecutionOrReturningSuccess() {
        IllegalStateException failure = new IllegalStateException("Acknowledgement failed");
        WorkUnitResult result = new WorkUnitResult(unit.migrationId(), unit.workUnitId(), 5, 5);
        when(workQueue.receive()).thenReturn(Optional.of(delivery));
        when(workUnitExecutor.execute(unit, plan)).thenReturn(result);
        doThrow(failure).when(workQueue).acknowledge(delivery);

        assertSame(failure, assertThrows(IllegalStateException.class, consumer::processOne));

        InOrder order = inOrder(workQueue, workUnitExecutor);
        order.verify(workQueue).receive();
        order.verify(workQueue).maintainVisibility(org.mockito.ArgumentMatchers.any(Delivery.class));
        order.verify(workUnitExecutor).execute(same(unit), same(plan));
        order.verify(workQueue).acknowledge(same(delivery));
        verifyNoMoreInteractions(workQueue, workUnitExecutor);
    }

    @Test
    void processesRedeliveryWithItsCurrentAcknowledgementToken() {
        Delivery redelivery = new Delivery(message, "receipt-second");
        WorkUnitResult result = new WorkUnitResult(unit.migrationId(), unit.workUnitId(), 5, 5);
        when(workQueue.receive()).thenReturn(Optional.of(delivery)).thenReturn(Optional.of(redelivery));
        when(workUnitExecutor.execute(unit, plan)).thenReturn(result);

        assertSame(result, consumer.processOne().orElseThrow());
        assertSame(result, consumer.processOne().orElseThrow());

        InOrder order = inOrder(workQueue, workUnitExecutor);
        order.verify(workQueue).receive();
        order.verify(workQueue).maintainVisibility(org.mockito.ArgumentMatchers.any(Delivery.class));
        order.verify(workUnitExecutor).execute(same(unit), same(plan));
        order.verify(workQueue).acknowledge(same(delivery));
        order.verify(workQueue).receive();
        order.verify(workQueue).maintainVisibility(org.mockito.ArgumentMatchers.any(Delivery.class));
        order.verify(workUnitExecutor).execute(same(unit), same(plan));
        order.verify(workQueue).acknowledge(same(redelivery));
        verifyNoMoreInteractions(workQueue, workUnitExecutor);
    }
}
