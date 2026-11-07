package dev.borja.nosqlbridge.worker.execution;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.MDC;

import dev.borja.nosqlbridge.worker.mapping.MappingEngine;
import dev.borja.nosqlbridge.common.model.LogicalRecord;
import dev.borja.nosqlbridge.common.plan.BridgeExecutionPlan;
import dev.borja.nosqlbridge.common.registry.TechnologyRegistry;
import dev.borja.nosqlbridge.common.source.connector.RecordCursor;
import dev.borja.nosqlbridge.common.source.connector.SourceConnector;
import dev.borja.nosqlbridge.common.target.TargetConnector;
import dev.borja.nosqlbridge.common.work.WorkUnit;
import dev.borja.nosqlbridge.worker.work.WorkUnitResult;

public class WorkUnitExecutor {

    private final TechnologyRegistry technologyRegistry;
    private final MappingEngine mappingEngine;
    private final int batchSize;

    public WorkUnitExecutor(TechnologyRegistry technologyRegistry, MappingEngine mappingEngine, int batchSize) {
        if (technologyRegistry == null) {
            throw new IllegalArgumentException("Technology registry must not be null.");
        }
        if (mappingEngine == null) {
            throw new IllegalArgumentException("Mapping engine must not be null.");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Execution batch size must be positive.");
        }
        this.technologyRegistry = technologyRegistry;
        this.mappingEngine = mappingEngine;
        this.batchSize = batchSize;
    }

    public WorkUnitResult execute(WorkUnit workUnit, BridgeExecutionPlan plan) {
        if (workUnit == null) {
            throw new IllegalArgumentException("Work unit must not be null.");
        }
        if (plan == null || plan.source() == null || plan.target() == null || plan.mappings() == null) {
            throw new IllegalArgumentException("Execution plan, source, target and mappings must not be null.");
        }
        phase("source_setup");
        SourceConnector source = technologyRegistry.sourceFactory(plan.source().technology())
                .createConnector(plan.source());
        phase("target_setup");
        TargetConnector target = technologyRegistry.targetFactory(plan.target().technology()).create(plan.target());
        List<LogicalRecord> buffer = new ArrayList<>(batchSize);
        long recordsRead = 0;
        long recordsWritten = 0;
        phase("source_open");
        try (RecordCursor cursor = source.open(workUnit.sourcePartition())) {
            phase("source_read");
            while (cursor.hasNext()) {
                LogicalRecord sourceRecord = cursor.next();
                recordsRead++;
                if (MDC.get("workUnitId") != null) MDC.put("recordsReadSoFar", Long.toString(recordsRead));
                phase("transform");
                buffer.add(mappingEngine.map(sourceRecord, plan.mappings(), plan.nullPolicy(), plan.unmappedPolicy()));
                if (buffer.size() == batchSize) {
                    phase("target_write");
                    recordsWritten += flush(target, buffer);
                    if (MDC.get("workUnitId") != null) MDC.put("recordsWrittenConfirmedSoFar", Long.toString(recordsWritten));
                }
                phase("source_read");
            }
            if (!buffer.isEmpty()) {
                phase("target_write");
                recordsWritten += flush(target, buffer);
                if (MDC.get("workUnitId") != null) MDC.put("recordsWrittenConfirmedSoFar", Long.toString(recordsWritten));
            }
            phase("source_close");
        }
        return new WorkUnitResult(workUnit.migrationId(), workUnit.workUnitId(), recordsRead, recordsWritten);
    }

    private static void phase(String phase) {
        // WorkerConsumer owns and restores this MDC scope, including on failures.
        if (MDC.get("workUnitId") != null) MDC.put("phase", phase);
    }

    private int flush(TargetConnector target, List<LogicalRecord> buffer) {
        int count = buffer.size();
        target.writeBatch(List.copyOf(buffer));
        buffer.clear();
        return count;
    }
}
