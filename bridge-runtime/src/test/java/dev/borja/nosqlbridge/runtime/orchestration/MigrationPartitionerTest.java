package dev.borja.nosqlbridge.runtime.orchestration;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.borja.nosqlbridge.runtime.plan.BridgeExecutionPlan;
import dev.borja.nosqlbridge.runtime.plan.ConnectorPlan;
import dev.borja.nosqlbridge.runtime.registry.TechnologyRegistry;
import dev.borja.nosqlbridge.runtime.source.partitioning.SourcePartitionCursor;
import dev.borja.nosqlbridge.runtime.source.partitioning.SourcePartitioner;
import dev.borja.nosqlbridge.runtime.source.SourceTechnologyFactory;
import dev.borja.nosqlbridge.runtime.source.connector.SourceConnector;
import dev.borja.nosqlbridge.runtime.work.MigrationId;
import dev.borja.nosqlbridge.runtime.work.SourcePartition;
import dev.borja.nosqlbridge.runtime.work.WorkUnit;
import dev.borja.nosqlbridge.runtime.work.WorkUnitId;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MigrationPartitionerTest {

    @Mock
    private SourceTechnologyFactory factory;
    @Mock
    private SourcePartitioner source;
    @Mock
    private SourcePartitionCursor partitions;

    private final MigrationId migrationId = new MigrationId(
            UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
    private final ConnectorPlan sourcePlan = new ConnectorPlan("test-source", Map.of());
    private final BridgeExecutionPlan plan = new BridgeExecutionPlan(1, "test", sourcePlan,
            new ConnectorPlan("test-target", Map.of()), "drop", "omit", List.of());
    private MigrationPartitioner partitioner;

    @BeforeEach
    void setUp() {
        when(factory.technology()).thenReturn("test-source");
        partitioner = new MigrationPartitioner(new TechnologyRegistry(List.of(factory), List.of()));
    }

    @Test
    void deliversUnitsOneAtATimeWithSequentialIdsAndExactPartitions() {
        SourcePartition first = new SourcePartition("test-range", Map.of("upper", "10"));
        SourcePartition second = new SourcePartition("test-range", Map.of("lower", "10", "upper", "20"));
        SourcePartition third = new SourcePartition("test-range", Map.of("lower", "20", "upper", "30"));
        stubOpen();
        Iterator<SourcePartition> remaining = List.of(first, second, third).iterator();
        when(partitions.hasNext()).thenAnswer(invocation -> remaining.hasNext());
        when(partitions.next()).thenAnswer(invocation -> remaining.next());

        try (WorkUnitCursor units = partitioner.open(plan, migrationId, 10)) {
            verifyNoInteractions(partitions);
            assertTrue(units.hasNext());
            assertTrue(units.hasNext());
            verify(partitions, never()).next();

            WorkUnit firstUnit = units.next();
            assertEquals(new WorkUnit(migrationId, new WorkUnitId(0), first), firstUnit);
            assertSame(first, firstUnit.sourcePartition());
            verify(partitions, times(1)).next();
            verify(partitions, never()).close();

            assertEquals(new WorkUnit(migrationId, new WorkUnitId(1), second), units.next());
            verify(partitions, times(2)).next();
            List<WorkUnit> remainingUnits = new ArrayList<>();
            units.forEachRemaining(remainingUnits::add);
            assertEquals(List.of(new WorkUnit(migrationId, new WorkUnitId(2), third)), remainingUnits);
            verify(partitions, times(3)).next();
            assertFalse(units.hasNext());
            verify(partitions).close();
            assertFalse(units.hasNext());
            assertThrows(NoSuchElementException.class, units::next);
        }

        verify(source).open(10);
        verify(factory).createPartitioner(sourcePlan);
        verify(factory, never()).createConnector(any());
        verify(partitions).close();
    }

    @Test
    void emptySourceProducesNoUnitsAndClosesPartitionCursor() {
        stubOpen();

        try (WorkUnitCursor units = partitioner.open(plan, migrationId, 10)) {
            assertFalse(units.hasNext());
            verify(partitions).close();
            assertThrows(NoSuchElementException.class, units::next);
        }

        verify(partitions, never()).next();
        verify(partitions).close();
    }

    @Test
    void earlyClosePropagatesOnceWithoutConsumingPartitions() {
        stubOpen();

        try (WorkUnitCursor units = partitioner.open(plan, migrationId, 10)) {
            units.close();
            assertThrows(IllegalStateException.class, units::hasNext);
            assertThrows(IllegalStateException.class, units::next);
        }

        verify(partitions).close();
        verify(partitions, never()).hasNext();
        verify(partitions, never()).next();
    }

    @Test
    void failureWhileDiscoveringNextPartitionClosesCursor() {
        stubOpen();
        IllegalArgumentException failure = new IllegalArgumentException("invalid source id");
        when(partitions.hasNext()).thenReturn(true);
        when(partitions.next()).thenThrow(failure);

        try (WorkUnitCursor units = partitioner.open(plan, migrationId, 10)) {
            assertSame(failure, assertThrows(IllegalArgumentException.class, units::next));
            verify(partitions).close();
        }
    }

    @Test
    void failureWhileCheckingAvailabilityClosesCursor() {
        stubOpen();
        IllegalStateException failure = new IllegalStateException("source unavailable");
        when(partitions.hasNext()).thenThrow(failure);

        try (WorkUnitCursor units = partitioner.open(plan, migrationId, 10)) {
            assertSame(failure, assertThrows(IllegalStateException.class, units::hasNext));
            verify(partitions).close();
        }
    }

    @Test
    void keepsPlanningFailureWhenCleanupAlsoFails() {
        stubOpen();
        IllegalStateException failure = new IllegalStateException("source unavailable");
        IllegalStateException cleanupFailure = new IllegalStateException("close failed");
        when(partitions.hasNext()).thenThrow(failure);
        doThrow(cleanupFailure).when(partitions).close();

        try (WorkUnitCursor units = partitioner.open(plan, migrationId, 10)) {
            assertSame(failure, assertThrows(IllegalStateException.class, units::hasNext));
            assertArrayEquals(new Throwable[] {cleanupFailure}, failure.getSuppressed());
        }
        verify(partitions).close();
    }

    @Test
    void rejectsInvalidArgumentsBeforeResolvingPartitioner() {
        assertThrows(IllegalArgumentException.class, () -> new MigrationPartitioner(null));
        assertThrows(IllegalArgumentException.class, () -> partitioner.open(null, migrationId, 10));
        BridgeExecutionPlan missingSource = new BridgeExecutionPlan(1, "test", null, plan.target(),
                "drop", "omit", List.of());
        assertThrows(IllegalArgumentException.class, () -> partitioner.open(missingSource, migrationId, 10));
        assertThrows(IllegalArgumentException.class, () -> partitioner.open(plan, null, 10));
        assertThrows(IllegalArgumentException.class, () -> partitioner.open(plan, migrationId, 0));
        assertThrows(IllegalArgumentException.class, () -> partitioner.open(plan, migrationId, -1));

        verify(factory, never()).createPartitioner(any());
        verifyNoInteractions(source, partitions);
    }

    @Test
    void rejectsUnknownSourceTechnology() {
        BridgeExecutionPlan unknown = new BridgeExecutionPlan(1, "test", new ConnectorPlan("unknown", Map.of()),
                plan.target(), "drop", "omit", List.of());

        assertThrows(IllegalArgumentException.class, () -> partitioner.open(unknown, migrationId, 10));
        verifyNoInteractions(source, partitions);
    }

    @Test
    void failsExplicitlyForFactoryWithoutPartitionSupport() {
        SourceTechnologyFactory unsupported = new SourceTechnologyFactory() {
            public String technology() { return "test-source"; }
            public SourceConnector createConnector(ConnectorPlan config) {
                throw new AssertionError("Planning must not create a record reader.");
            }
        };
        MigrationPartitioner unsupportedPartitioner = new MigrationPartitioner(
                new TechnologyRegistry(List.of(unsupported), List.of()));

        assertThrows(UnsupportedOperationException.class,
                () -> unsupportedPartitioner.open(plan, migrationId, 10));
    }

    private void stubOpen() {
        when(factory.createPartitioner(sourcePlan)).thenReturn(source);
        when(source.open(10)).thenReturn(partitions);
    }
}
