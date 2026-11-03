package dev.borja.nosqlbridge.orchestrator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import tools.jackson.databind.ObjectMapper;

import dev.borja.nosqlbridge.common.plan.BridgeExecutionPlan;
import dev.borja.nosqlbridge.common.plan.ConnectorPlan;
import dev.borja.nosqlbridge.common.plan.PlanValidator;
import dev.borja.nosqlbridge.common.queue.WorkQueue;
import dev.borja.nosqlbridge.common.registry.TechnologyRegistry;
import dev.borja.nosqlbridge.common.source.SourceTechnologyFactory;
import dev.borja.nosqlbridge.common.source.partitioning.SourcePartitionCursor;
import dev.borja.nosqlbridge.common.source.partitioning.SourcePartitioner;
import dev.borja.nosqlbridge.common.work.SourcePartition;
import dev.borja.nosqlbridge.common.work.WorkUnitId;
import dev.borja.nosqlbridge.common.work.WorkUnitMessage;
import dev.borja.nosqlbridge.common.work.WorkUnitMessageSerializer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class MigrationPublisherTest {

    private final WorkQueue queue = mock(WorkQueue.class);
    private final SourceTechnologyFactory factory = mock(SourceTechnologyFactory.class);
    private final SourcePartitioner source = mock(SourcePartitioner.class);
    private final SourcePartitionCursor partitions = mock(SourcePartitionCursor.class);
    private final AtomicInteger generated = new AtomicInteger();
    private final List<WorkUnitMessage> messages = new ArrayList<>();
    private final BridgeExecutionPlan plan = new BridgeExecutionPlan(1, "test",
            new ConnectorPlan("test-source", Map.of()), new ConnectorPlan("dynamodb", Map.of("table", "Products")),
            "drop", "omit", List.of());
    private MigrationPartitioner partitioner;
    private MigrationPublisher publisher;

    @BeforeEach
    void setUp() {
        when(factory.technology()).thenReturn("test-source");
        partitioner = new MigrationPartitioner(new TechnologyRegistry(List.of(factory), List.of()));
        publisher = new MigrationPublisher(new PlanValidator(), partitioner, queue, 2);
    }

    @Test
    void publishesIncrementallyWithSharedMigrationSequentialIdsAndWorkerCompatibleMessages() {
        preparePartitions(3);
        WorkUnitMessageSerializer serializer = new WorkUnitMessageSerializer(new ObjectMapper());
        doAnswer(invocation -> {
            WorkUnitMessage message = invocation.getArgument(0);
            assertEquals(messages.size() + 1, generated.get());
            assertSame(plan, message.plan());
            assertEquals(message, serializer.deserialize(serializer.serialize(message)));
            messages.add(message);
            return null;
        }).when(queue).publish(any(WorkUnitMessage.class));

        MigrationPublisher.Result result = publisher.publish(plan);

        assertEquals(3, result.publishedMessages());
        for (int index = 0; index < messages.size(); index++) {
            WorkUnitMessage message = messages.get(index);
            assertEquals(result.migrationId(), message.workUnit().migrationId());
            assertEquals(new WorkUnitId(index), message.workUnit().workUnitId());
            assertEquals(partition(index), message.workUnit().sourcePartition());
        }
        InOrder order = inOrder(partitions, queue);
        for (WorkUnitMessage message : messages) {
            order.verify(partitions).next();
            order.verify(queue).publish(message);
        }
        verify(partitions).close();
        verify(factory).createPartitioner(plan.source());
        verify(factory, never()).createConnector(any());
        verifyNoMoreInteractions(queue);
    }

    @Test
    void generatesOneDifferentMigrationIdPerExecutionEvenForEmptySources() {
        preparePartitions(0);

        MigrationPublisher.Result first = publisher.publish(plan);
        MigrationPublisher.Result second = publisher.publish(plan);

        assertNotEquals(first.migrationId(), second.migrationId());
        assertEquals(4, first.migrationId().value().version());
        assertEquals(0, first.publishedMessages());
        assertEquals(0, second.publishedMessages());
        verify(partitions, times(2)).close();
        verifyNoInteractions(queue);
    }

    @Test
    void validatesBeforeOpeningPartitionsOrPublishing() {
        BridgeExecutionPlan invalid = new BridgeExecutionPlan(2, "invalid", plan.source(), plan.target(),
                "drop", "omit", List.of());
        BridgeExecutionPlan missingMappings = new BridgeExecutionPlan(1, "invalid", plan.source(), plan.target(),
                "drop", "omit", null);

        assertThrows(IllegalArgumentException.class, () -> publisher.publish(null));
        assertThrows(IllegalArgumentException.class, () -> publisher.publish(invalid));
        assertThrows(IllegalArgumentException.class, () -> publisher.publish(missingMappings));

        verify(factory, never()).createPartitioner(any());
        verifyNoInteractions(source, partitions, queue);
    }

    @Test
    void stopsAfterFailedPublicationAndReportsOnlyCompletedCallsWithoutLeakingCauseMessage() {
        preparePartitions(5);
        IllegalStateException failure = new IllegalStateException("sensitive connection details");
        doAnswer(invocation -> {
            if (messages.size() == 2) {
                throw failure;
            }
            messages.add(invocation.getArgument(0));
            return null;
        }).when(queue).publish(any(WorkUnitMessage.class));

        MigrationPublisher.PublicationException exception = assertThrows(MigrationPublisher.PublicationException.class,
                () -> publisher.publish(plan));

        assertEquals(2, exception.progress().publishedMessages());
        assertEquals(messages.getFirst().workUnit().migrationId(), exception.progress().migrationId());
        assertEquals(3, generated.get());
        assertSame(failure, exception.getCause());
        assertTrue(exception.getMessage().contains("Delivery of a failed operation may be uncertain"));
        assertFalse(exception.getMessage().contains("sensitive connection details"));
        verify(queue, times(3)).publish(any(WorkUnitMessage.class));
        verify(partitions).close();
        verifyNoMoreInteractions(queue);
    }

    @Test
    void stopsAndClosesCursorWhenPartitioningFailsAfterTwoPublications() {
        preparePartitions(5);
        IllegalArgumentException failure = new IllegalArgumentException("Invalid partition");
        when(partitions.hasNext()).thenAnswer(invocation -> {
            if (generated.get() == 2) {
                throw failure;
            }
            return true;
        });

        MigrationPublisher.PublicationException exception = assertThrows(MigrationPublisher.PublicationException.class,
                () -> publisher.publish(plan));

        assertEquals(2, exception.progress().publishedMessages());
        assertSame(failure, exception.getCause());
        verify(queue, times(2)).publish(any(WorkUnitMessage.class));
        verify(partitions).close();
        verifyNoMoreInteractions(queue);
    }

    @Test
    void reportsMigrationWithZeroPublicationsIfOpeningSourceFails() {
        IllegalStateException failure = new IllegalStateException("Source unavailable");
        when(factory.createPartitioner(plan.source())).thenThrow(failure);

        MigrationPublisher.PublicationException exception = assertThrows(MigrationPublisher.PublicationException.class,
                () -> publisher.publish(plan));

        assertEquals(0, exception.progress().publishedMessages());
        assertSame(failure, exception.getCause());
        verifyNoInteractions(queue);
    }

    @Test
    void preservesPublicationFailureWhenClosingAlsoFails() {
        preparePartitions(1);
        IllegalStateException failure = new IllegalStateException("Queue unavailable");
        IllegalStateException closeFailure = new IllegalStateException("Close failed");
        doThrow(failure).when(queue).publish(any(WorkUnitMessage.class));
        doThrow(closeFailure).when(partitions).close();

        MigrationPublisher.PublicationException exception = assertThrows(MigrationPublisher.PublicationException.class,
                () -> publisher.publish(plan));

        assertEquals(0, exception.progress().publishedMessages());
        assertSame(failure, exception.getCause());
        assertEquals(List.of(closeFailure), List.of(failure.getSuppressed()));
        verify(partitions).close();
    }

    @Test
    void rejectsMissingDependenciesAndInvalidPartitionSize() {
        assertThrows(IllegalArgumentException.class, () -> new MigrationPublisher(null, partitioner, queue, 2));
        assertThrows(IllegalArgumentException.class, () -> new MigrationPublisher(new PlanValidator(), null, queue, 2));
        assertThrows(IllegalArgumentException.class, () -> new MigrationPublisher(new PlanValidator(), partitioner, null, 2));
        assertThrows(IllegalArgumentException.class, () -> new MigrationPublisher(new PlanValidator(), partitioner, queue, 0));
        assertThrows(IllegalArgumentException.class, () -> new MigrationPublisher(new PlanValidator(), partitioner, queue, -1));
    }

    private void preparePartitions(int count) {
        when(factory.createPartitioner(plan.source())).thenReturn(source);
        when(source.open(2)).thenReturn(partitions);
        when(partitions.hasNext()).thenAnswer(invocation -> generated.get() < count);
        when(partitions.next()).thenAnswer(invocation -> partition(generated.getAndIncrement()));
    }

    private SourcePartition partition(int index) {
        return new SourcePartition("test", Map.of("index", Integer.toString(index)));
    }
}
