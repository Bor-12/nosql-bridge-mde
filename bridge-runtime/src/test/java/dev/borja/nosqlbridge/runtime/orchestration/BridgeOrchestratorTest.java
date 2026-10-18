package dev.borja.nosqlbridge.runtime.orchestration;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.borja.nosqlbridge.runtime.connector.RecordCursor;
import dev.borja.nosqlbridge.runtime.connector.SourceConnector;
import dev.borja.nosqlbridge.runtime.connector.TargetConnector;
import dev.borja.nosqlbridge.runtime.mapping.MappingEngine;
import dev.borja.nosqlbridge.runtime.model.LogicalRecord;
import dev.borja.nosqlbridge.runtime.model.LogicalValue;
import dev.borja.nosqlbridge.runtime.plan.BridgeExecutionPlan;
import dev.borja.nosqlbridge.runtime.plan.ConnectorPlan;
import dev.borja.nosqlbridge.runtime.plan.MappingPlan;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BridgeOrchestratorTest {

    @Mock
    private SourceConnector sourceConnector;
    @Mock
    private TargetConnector targetConnector;
    @Mock
    private MappingEngine mappingEngine;
    @Mock
    private RecordCursor cursor;

    private BridgeOrchestrator orchestrator;
    private BridgeExecutionPlan plan;

    @BeforeEach
    void setUp() {
        orchestrator = new BridgeOrchestrator(sourceConnector, targetConnector, mappingEngine);
        plan = new BridgeExecutionPlan(1, "test-bridge",
                new ConnectorPlan("source", Map.of()),
                new ConnectorPlan("target", Map.of()),
                "drop", "preserve", List.of(new MappingPlan("name", "title", null, null)));
    }

    @Test
    void rejectsNullPlanBeforeOpeningSource() {
        assertThrows(IllegalArgumentException.class, () -> orchestrator.execute(null));

        verifyNoInteractions(sourceConnector, targetConnector, mappingEngine, cursor);
    }

    @Test
    void opensAndClosesEmptyCursorWithoutMappingOrWriting() {
        when(sourceConnector.open()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(false);

        orchestrator.execute(plan);

        verify(sourceConnector).open();
        verify(cursor, never()).next();
        verify(cursor).close();
        verifyNoInteractions(mappingEngine, targetConnector);
    }

    @Test
    void mapsOneRecordWithExactPlanArgumentsAndWritesReturnedInstance() {
        LogicalRecord sourceRecord = record("name", "first");
        LogicalRecord mappedRecord = record("title", "FIRST");
        when(sourceConnector.open()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(sourceRecord);
        when(mappingEngine.map(same(sourceRecord), same(plan.mappings()),
                same(plan.nullPolicy()), same(plan.unmappedPolicy()))).thenReturn(mappedRecord);

        orchestrator.execute(plan);

        verify(cursor).next();
        verify(mappingEngine).map(same(sourceRecord), same(plan.mappings()),
                same(plan.nullPolicy()), same(plan.unmappedPolicy()));
        verify(targetConnector).write(same(mappedRecord));
        verify(cursor).close();
        verifyNoMoreInteractions(mappingEngine, targetConnector);
    }

    @Test
    void mapsAndWritesEachRecordBeforeReadingTheNextOne() {
        LogicalRecord first = record("name", "first");
        LogicalRecord second = record("name", "second");
        LogicalRecord third = record("name", "third");
        LogicalRecord mappedFirst = record("title", "FIRST");
        LogicalRecord mappedSecond = record("title", "SECOND");
        LogicalRecord mappedThird = record("title", "THIRD");
        when(sourceConnector.open()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, true, true, false);
        when(cursor.next()).thenReturn(first, second, third);
        when(mappingEngine.map(first, plan.mappings(), plan.nullPolicy(), plan.unmappedPolicy()))
                .thenReturn(mappedFirst);
        when(mappingEngine.map(second, plan.mappings(), plan.nullPolicy(), plan.unmappedPolicy()))
                .thenReturn(mappedSecond);
        when(mappingEngine.map(third, plan.mappings(), plan.nullPolicy(), plan.unmappedPolicy()))
                .thenReturn(mappedThird);

        orchestrator.execute(plan);

        InOrder order = inOrder(sourceConnector, cursor, mappingEngine, targetConnector);
        order.verify(sourceConnector).open();
        order.verify(cursor).hasNext();
        order.verify(cursor).next();
        order.verify(mappingEngine).map(first, plan.mappings(), plan.nullPolicy(), plan.unmappedPolicy());
        order.verify(targetConnector).write(same(mappedFirst));
        order.verify(cursor).hasNext();
        order.verify(cursor).next();
        order.verify(mappingEngine).map(second, plan.mappings(), plan.nullPolicy(), plan.unmappedPolicy());
        order.verify(targetConnector).write(same(mappedSecond));
        order.verify(cursor).hasNext();
        order.verify(cursor).next();
        order.verify(mappingEngine).map(third, plan.mappings(), plan.nullPolicy(), plan.unmappedPolicy());
        order.verify(targetConnector).write(same(mappedThird));
        order.verify(cursor).hasNext();
        order.verify(cursor).close();
        verifyNoMoreInteractions(sourceConnector, cursor, mappingEngine, targetConnector);
    }

    @Test
    void propagatesMappingFailureAndClosesCursorWithoutWriting() {
        LogicalRecord sourceRecord = record("name", "first");
        IllegalArgumentException failure = new IllegalArgumentException("Mapping failed");
        when(sourceConnector.open()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true);
        when(cursor.next()).thenReturn(sourceRecord);
        when(mappingEngine.map(sourceRecord, plan.mappings(), plan.nullPolicy(), plan.unmappedPolicy()))
                .thenThrow(failure);

        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> orchestrator.execute(plan)));

        verify(cursor).hasNext();
        verify(cursor).next();
        verify(cursor).close();
        verifyNoMoreInteractions(cursor);
        verifyNoInteractions(targetConnector);
    }

    @Test
    void propagatesWriteFailureAndStopsBeforeReadingAnotherRecord() {
        LogicalRecord sourceRecord = record("name", "first");
        LogicalRecord mappedRecord = record("title", "FIRST");
        IllegalStateException failure = new IllegalStateException("Write failed");
        when(sourceConnector.open()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true);
        when(cursor.next()).thenReturn(sourceRecord);
        when(mappingEngine.map(sourceRecord, plan.mappings(), plan.nullPolicy(), plan.unmappedPolicy()))
                .thenReturn(mappedRecord);
        doThrow(failure).when(targetConnector).write(mappedRecord);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> orchestrator.execute(plan)));

        verify(cursor).hasNext();
        verify(cursor).next();
        verify(cursor).close();
        verify(mappingEngine).map(sourceRecord, plan.mappings(), plan.nullPolicy(), plan.unmappedPolicy());
        verify(targetConnector).write(mappedRecord);
        verifyNoMoreInteractions(cursor, mappingEngine, targetConnector);
    }

    @Test
    void propagatesOpenFailureWithoutMappingOrWriting() {
        IllegalStateException failure = new IllegalStateException("Open failed");
        when(sourceConnector.open()).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> orchestrator.execute(plan)));

        verify(sourceConnector).open();
        verifyNoInteractions(cursor, mappingEngine, targetConnector);
    }

    @Test
    void propagatesHasNextFailureAndClosesCursor() {
        IllegalStateException failure = new IllegalStateException("hasNext failed");
        when(sourceConnector.open()).thenReturn(cursor);
        when(cursor.hasNext()).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> orchestrator.execute(plan)));

        verify(cursor, never()).next();
        verify(cursor).close();
        verifyNoInteractions(mappingEngine, targetConnector);
    }

    @Test
    void propagatesNextFailureAndClosesCursor() {
        IllegalStateException failure = new IllegalStateException("Next failed");
        when(sourceConnector.open()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true);
        when(cursor.next()).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> orchestrator.execute(plan)));

        verify(cursor).close();
        verifyNoInteractions(mappingEngine, targetConnector);
    }

    private LogicalRecord record(String field, String value) {
        return new LogicalRecord(Map.of(field, new LogicalValue.StringValue(value)));
    }
}
