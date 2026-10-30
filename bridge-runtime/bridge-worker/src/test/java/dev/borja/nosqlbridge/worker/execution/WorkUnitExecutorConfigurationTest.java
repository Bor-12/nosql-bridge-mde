package dev.borja.nosqlbridge.worker.execution;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import dev.borja.nosqlbridge.worker.config.ExecutionConfiguration;
import dev.borja.nosqlbridge.common.model.LogicalRecord;
import dev.borja.nosqlbridge.common.model.LogicalValue;
import dev.borja.nosqlbridge.common.plan.BridgeExecutionPlan;
import dev.borja.nosqlbridge.common.plan.ConnectorPlan;
import dev.borja.nosqlbridge.common.source.SourceTechnologyFactory;
import dev.borja.nosqlbridge.common.source.connector.RecordCursor;
import dev.borja.nosqlbridge.common.source.connector.SourceConnector;
import dev.borja.nosqlbridge.common.target.TargetConnector;
import dev.borja.nosqlbridge.common.target.TargetConnectorFactory;
import dev.borja.nosqlbridge.common.work.MigrationId;
import dev.borja.nosqlbridge.common.work.SourcePartition;
import dev.borja.nosqlbridge.common.work.WorkUnit;
import dev.borja.nosqlbridge.common.work.WorkUnitId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkUnitExecutorConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ExecutionConfiguration.class);

    @ParameterizedTest
    @CsvSource({"'', 25", "bridge.execution.batch-size=2, 2"})
    void usesDefaultOrConfiguredBatchSize(String property, int expectedSize) {
        SourceTechnologyFactory sourceFactory = mock(SourceTechnologyFactory.class);
        TargetConnectorFactory targetFactory = mock(TargetConnectorFactory.class);
        SourceConnector source = mock(SourceConnector.class);
        TargetConnector target = mock(TargetConnector.class);
        RecordCursor cursor = mock(RecordCursor.class);
        BridgeExecutionPlan plan = new BridgeExecutionPlan(1, "test", new ConnectorPlan("test-source", Map.of()),
                new ConnectorPlan("test-target", Map.of()), "preserve", "omit", List.of());
        WorkUnit unit = new WorkUnit(new MigrationId(UUID.fromString("550e8400-e29b-41d4-a716-446655440000")),
                new WorkUnitId(0), new SourcePartition("test", Map.of()));
        when(sourceFactory.technology()).thenReturn("test-source");
        when(targetFactory.technology()).thenReturn("test-target");
        when(sourceFactory.createConnector(plan.source())).thenReturn(source);
        when(targetFactory.create(plan.target())).thenReturn(target);
        when(source.open(unit.sourcePartition())).thenReturn(cursor);
        List<LogicalRecord> records = new ArrayList<>();
        for (int index = 0; index <= expectedSize; index++) {
            records.add(new LogicalRecord(Map.of("id", new LogicalValue.IntegerValue(index))));
        }
        Iterator<LogicalRecord> remaining = records.iterator();
        when(cursor.hasNext()).thenAnswer(invocation -> remaining.hasNext());
        when(cursor.next()).thenAnswer(invocation -> remaining.next());
        List<Integer> batchSizes = new ArrayList<>();
        doAnswer(invocation -> {
            List<LogicalRecord> batch = invocation.getArgument(0);
            batchSizes.add(batch.size());
            return null;
        }).when(target).writeBatch(anyList());
        ApplicationContextRunner configured = contextRunner.withBean(SourceTechnologyFactory.class, () -> sourceFactory)
                .withBean(TargetConnectorFactory.class, () -> targetFactory);
        if (!property.isEmpty()) {
            configured = configured.withPropertyValues(property);
        }

        configured.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(expectedSize + 1, context.getBean(WorkUnitExecutor.class).execute(unit, plan).recordsWritten());
            assertEquals(List.of(expectedSize, 1), batchSizes);
            verify(cursor).close();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "invalid"})
    void rejectsInvalidConfiguredBatchSize(String batchSize) {
        contextRunner.withPropertyValues("bridge.execution.batch-size=" + batchSize)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }
}
