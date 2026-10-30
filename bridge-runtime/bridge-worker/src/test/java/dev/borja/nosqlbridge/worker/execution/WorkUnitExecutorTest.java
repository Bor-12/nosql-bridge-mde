package dev.borja.nosqlbridge.worker.execution;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.borja.nosqlbridge.worker.mapping.DefaultValueConverter;
import dev.borja.nosqlbridge.worker.mapping.MappingEngine;
import dev.borja.nosqlbridge.worker.model.LogicalPathResolver;
import dev.borja.nosqlbridge.common.model.LogicalRecord;
import dev.borja.nosqlbridge.common.model.LogicalValue;
import dev.borja.nosqlbridge.common.plan.BridgeExecutionPlan;
import dev.borja.nosqlbridge.common.plan.ConnectorPlan;
import dev.borja.nosqlbridge.common.plan.MappingPlan;
import dev.borja.nosqlbridge.common.registry.TechnologyRegistry;
import dev.borja.nosqlbridge.common.source.SourceTechnologyFactory;
import dev.borja.nosqlbridge.common.source.connector.RecordCursor;
import dev.borja.nosqlbridge.common.source.connector.SourceConnector;
import dev.borja.nosqlbridge.common.target.TargetConnector;
import dev.borja.nosqlbridge.common.target.TargetConnectorFactory;
import dev.borja.nosqlbridge.worker.transformation.TransformationEngine;
import dev.borja.nosqlbridge.common.work.MigrationId;
import dev.borja.nosqlbridge.common.work.SourcePartition;
import dev.borja.nosqlbridge.common.work.WorkUnit;
import dev.borja.nosqlbridge.common.work.WorkUnitId;
import dev.borja.nosqlbridge.worker.work.WorkUnitResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkUnitExecutorTest {

    @Mock
    private SourceTechnologyFactory sourceFactory;
    @Mock
    private TargetConnectorFactory targetFactory;
    @Mock
    private SourceConnector source;
    @Mock
    private TargetConnector target;
    @Mock
    private RecordCursor cursor;
    @Mock
    private MappingEngine mappingEngine;

    private final WorkUnit unit = new WorkUnit(
            new MigrationId(UUID.fromString("550e8400-e29b-41d4-a716-446655440000")), new WorkUnitId(2),
            new SourcePartition("test-range", Map.of("lower", "10", "upper", "20")));
    private final BridgeExecutionPlan plan = new BridgeExecutionPlan(1, "test",
            new ConnectorPlan("test-source", Map.of()), new ConnectorPlan("test-target", Map.of()),
            "drop", "omit", List.of(new MappingPlan("value", "result", null, null)));
    private TechnologyRegistry registry;
    private WorkUnitExecutor executor;

    @BeforeEach
    void setUp() {
        when(sourceFactory.technology()).thenReturn("test-source");
        when(targetFactory.technology()).thenReturn("test-target");
        registry = new TechnologyRegistry(List.of(sourceFactory), List.of(targetFactory));
        executor = new WorkUnitExecutor(registry, mappingEngine, 3);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 3, 8})
    void executesOnlyRequestedPartitionWithBoundedOrderedBatches(int count) {
        stubConnectors();
        List<LogicalRecord> mapped = stubRecords(count);

        WorkUnitResult result = executor.execute(unit, plan);

        assertEquals(new WorkUnitResult(unit.migrationId(), unit.workUnitId(), count, count), result);
        verify(sourceFactory).createConnector(plan.source());
        verify(targetFactory).create(plan.target());
        verify(source).open(unit.sourcePartition());
        verify(source, never()).open();
        verify(cursor).close();
        for (int index = 0; index < count; index++) {
            verify(mappingEngine).map(record("value", Integer.toString(index)), plan.mappings(),
                    plan.nullPolicy(), plan.unmappedPolicy());
        }
        if (count == 0) {
            verifyNoInteractions(mappingEngine, target);
        } else {
            ArgumentCaptor<List<LogicalRecord>> batches = ArgumentCaptor.captor();
            verify(target, times((count + 2) / 3)).writeBatch(batches.capture());
            List<LogicalRecord> written = new ArrayList<>();
            for (int offset = 0, index = 0; offset < count; offset += 3, index++) {
                List<LogicalRecord> batch = batches.getAllValues().get(index);
                assertEquals(mapped.subList(offset, Math.min(offset + 3, count)), batch);
                written.addAll(batch);
            }
            assertEquals(mapped, written);
            verify(target, never()).write(any(LogicalRecord.class));
        }
    }

    @Test
    void writesFullBufferBeforeReadingMoreRecords() {
        stubConnectors();
        stubRecords(4);

        executor.execute(unit, plan);

        InOrder order = inOrder(cursor, target);
        for (int index = 0; index < 3; index++) {
            order.verify(cursor).hasNext();
            order.verify(cursor).next();
        }
        order.verify(target).writeBatch(anyList());
        order.verify(cursor).hasNext();
        order.verify(cursor).next();
        order.verify(cursor).hasNext();
        order.verify(target).writeBatch(anyList());
        order.verify(cursor).close();
    }

    @Test
    void appliesPlanMappingTransformationAndPoliciesThroughExistingEngine() {
        stubConnectors();
        MappingEngine realEngine = new MappingEngine(new LogicalPathResolver(), new TransformationEngine(),
                new DefaultValueConverter());
        BridgeExecutionPlan mappingPlan = new BridgeExecutionPlan(1, "test", plan.source(), plan.target(),
                "drop", "omit", List.of(new MappingPlan("price", "amount", "toDecimal", null),
                        new MappingPlan("missing", "omitted", null, null)));
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(new LogicalRecord(Map.of("price", new LogicalValue.StringValue("19.90"),
                "extra", new LogicalValue.StringValue("drop"))));

        WorkUnitResult result = new WorkUnitExecutor(registry, realEngine, 3).execute(unit, mappingPlan);

        verify(target).writeBatch(List.of(new LogicalRecord(Map.of("amount",
                new LogicalValue.DecimalValue(new BigDecimal("19.90"))))));
        assertEquals(1, result.recordsWritten());
        verify(cursor).close();
    }

    @Test
    void propagatesReadFailureAfterConfirmedBatchWithoutResubmittingIt() {
        stubConnectors();
        stubRecords(3);
        IllegalStateException failure = new IllegalStateException("Read failed");
        when(cursor.hasNext()).thenReturn(true, true, true).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> executor.execute(unit, plan)));

        verify(target).writeBatch(anyList());
        verify(cursor).close();
    }

    @Test
    void propagatesNextFailureWithoutWritingAndClosesCursor() {
        stubConnectors();
        IllegalStateException failure = new IllegalStateException("Next failed");
        when(cursor.hasNext()).thenReturn(true);
        when(cursor.next()).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> executor.execute(unit, plan)));

        verifyNoInteractions(mappingEngine, target);
        verify(cursor).close();
    }

    @Test
    void propagatesMappingFailureWithoutFlushingIncompleteBuffer() {
        stubConnectors();
        stubRecords(2);
        IllegalArgumentException failure = new IllegalArgumentException("Mapping failed");
        when(mappingEngine.map(record("value", "1"), plan.mappings(), plan.nullPolicy(), plan.unmappedPolicy()))
                .thenThrow(failure);

        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> executor.execute(unit, plan)));

        verifyNoInteractions(target);
        verify(cursor).close();
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 4})
    void propagatesWriteFailureWithoutReadingOrRetryingFurther(int count) {
        stubConnectors();
        stubRecords(count);
        IllegalStateException failure = new IllegalStateException("Write failed");
        doThrow(failure).when(target).writeBatch(anyList());

        assertSame(failure, assertThrows(IllegalStateException.class, () -> executor.execute(unit, plan)));

        verify(target).writeBatch(anyList());
        verify(cursor, times(Math.min(count, 3))).next();
        verify(cursor).close();
    }

    @Test
    void propagatesCloseFailureInsteadOfReturningSuccessfulResult() {
        stubConnectors();
        stubRecords(1);
        IllegalStateException failure = new IllegalStateException("Close failed");
        doThrow(failure).when(cursor).close();

        assertSame(failure, assertThrows(IllegalStateException.class, () -> executor.execute(unit, plan)));

        verify(target).writeBatch(anyList());
    }

    @Test
    void preservesReadFailureAndSuppressesCloseFailure() {
        stubConnectors();
        IllegalStateException failure = new IllegalStateException("Read failed");
        IllegalStateException closeFailure = new IllegalStateException("Close failed");
        when(cursor.hasNext()).thenThrow(failure);
        doThrow(closeFailure).when(cursor).close();

        assertSame(failure, assertThrows(IllegalStateException.class, () -> executor.execute(unit, plan)));

        assertEquals(List.of(closeFailure), List.of(failure.getSuppressed()));
        verifyNoInteractions(mappingEngine, target);
    }

    @Test
    void rejectsMissingInputsBeforeCreatingConnectors() {
        assertThrows(IllegalArgumentException.class, () -> executor.execute(null, plan));
        assertThrows(IllegalArgumentException.class, () -> executor.execute(unit, null));
        assertThrows(IllegalArgumentException.class, () -> executor.execute(unit,
                new BridgeExecutionPlan(1, "test", null, plan.target(), "drop", "omit", List.of())));
        assertThrows(IllegalArgumentException.class, () -> executor.execute(unit,
                new BridgeExecutionPlan(1, "test", plan.source(), null, "drop", "omit", List.of())));
        assertThrows(IllegalArgumentException.class, () -> executor.execute(unit,
                new BridgeExecutionPlan(1, "test", plan.source(), plan.target(), "drop", "omit", null)));
        verify(sourceFactory, never()).createConnector(any());
        verify(targetFactory, never()).create(any());
        verifyNoInteractions(source, target, cursor, mappingEngine);
    }

    @Test
    void rejectsInvalidDependenciesAndBatchSizes() {
        assertThrows(IllegalArgumentException.class, () -> new WorkUnitExecutor(null, mappingEngine, 3));
        assertThrows(IllegalArgumentException.class, () -> new WorkUnitExecutor(registry, null, 3));
        assertThrows(IllegalArgumentException.class, () -> new WorkUnitExecutor(registry, mappingEngine, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorkUnitExecutor(registry, mappingEngine, -1));
    }

    private void stubConnectors() {
        when(sourceFactory.createConnector(plan.source())).thenReturn(source);
        when(targetFactory.create(plan.target())).thenReturn(target);
        when(source.open(unit.sourcePartition())).thenReturn(cursor);
    }

    private List<LogicalRecord> stubRecords(int count) {
        List<LogicalRecord> inputs = new ArrayList<>();
        List<LogicalRecord> outputs = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            LogicalRecord input = record("value", Integer.toString(index));
            LogicalRecord output = record("result", Integer.toString(index));
            inputs.add(input);
            outputs.add(output);
        }
        if (count > 0) {
            Iterator<LogicalRecord> mapped = outputs.iterator();
            when(mappingEngine.map(any(LogicalRecord.class), eq(plan.mappings()),
                    eq(plan.nullPolicy()), eq(plan.unmappedPolicy()))).thenAnswer(invocation -> mapped.next());
        }
        Iterator<LogicalRecord> remaining = inputs.iterator();
        when(cursor.hasNext()).thenAnswer(invocation -> remaining.hasNext());
        if (count > 0) {
            when(cursor.next()).thenAnswer(invocation -> remaining.next());
        }
        return outputs;
    }

    private LogicalRecord record(String name, String value) {
        return new LogicalRecord(Map.of(name, new LogicalValue.StringValue(value)));
    }
}
