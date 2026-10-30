package dev.borja.nosqlbridge.runtime.worker;

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

import dev.borja.nosqlbridge.runtime.execution.WorkUnitExecutor;
import dev.borja.nosqlbridge.runtime.plan.BridgeExecutionPlan;
import dev.borja.nosqlbridge.runtime.plan.ConnectorPlan;
import dev.borja.nosqlbridge.runtime.queue.WorkQueue;
import dev.borja.nosqlbridge.runtime.queue.WorkQueue.Delivery;
import dev.borja.nosqlbridge.runtime.work.MigrationId;
import dev.borja.nosqlbridge.runtime.work.SourcePartition;
import dev.borja.nosqlbridge.runtime.work.WorkUnit;
import dev.borja.nosqlbridge.runtime.work.WorkUnitId;
import dev.borja.nosqlbridge.runtime.work.WorkUnitMessage;
import dev.borja.nosqlbridge.runtime.work.WorkUnitResult;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerConsumerTest {

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
    void acknowledgesExactlyOnceAfterExecutionAndReturnsTheResult(long count) {
        WorkUnitResult expected = new WorkUnitResult(unit.migrationId(), unit.workUnitId(), count, count);
        when(workQueue.receive()).thenReturn(Optional.of(delivery));
        when(workUnitExecutor.execute(same(unit), same(plan))).thenReturn(expected);

        assertSame(expected, consumer.processOne().orElseThrow());

        InOrder order = inOrder(workQueue, workUnitExecutor);
        order.verify(workQueue).receive();
        order.verify(workUnitExecutor).execute(same(unit), same(plan));
        order.verify(workQueue).acknowledge(same(delivery));
        verifyNoMoreInteractions(workQueue, workUnitExecutor);
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
        IllegalArgumentException failure = new IllegalArgumentException("Execution failed");
        when(workQueue.receive()).thenReturn(Optional.of(delivery));
        when(workUnitExecutor.execute(unit, plan)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalArgumentException.class, consumer::processOne));

        verify(workQueue).receive();
        verify(workUnitExecutor).execute(same(unit), same(plan));
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
        order.verify(workUnitExecutor).execute(same(unit), same(plan));
        order.verify(workQueue).acknowledge(same(delivery));
        order.verify(workQueue).receive();
        order.verify(workUnitExecutor).execute(same(unit), same(plan));
        order.verify(workQueue).acknowledge(same(redelivery));
        verifyNoMoreInteractions(workQueue, workUnitExecutor);
    }
}
