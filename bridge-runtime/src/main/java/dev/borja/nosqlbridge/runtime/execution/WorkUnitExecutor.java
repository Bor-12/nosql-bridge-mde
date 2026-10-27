package dev.borja.nosqlbridge.runtime.execution;

import java.util.ArrayList;
import java.util.List;

import dev.borja.nosqlbridge.runtime.mapping.MappingEngine;
import dev.borja.nosqlbridge.runtime.model.LogicalRecord;
import dev.borja.nosqlbridge.runtime.plan.BridgeExecutionPlan;
import dev.borja.nosqlbridge.runtime.registry.TechnologyRegistry;
import dev.borja.nosqlbridge.runtime.source.connector.RecordCursor;
import dev.borja.nosqlbridge.runtime.source.connector.SourceConnector;
import dev.borja.nosqlbridge.runtime.target.TargetConnector;
import dev.borja.nosqlbridge.runtime.work.WorkUnit;
import dev.borja.nosqlbridge.runtime.work.WorkUnitResult;

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
        SourceConnector source = technologyRegistry.sourceFactory(plan.source().technology())
                .createConnector(plan.source());
        TargetConnector target = technologyRegistry.targetFactory(plan.target().technology()).create(plan.target());
        List<LogicalRecord> buffer = new ArrayList<>(batchSize);
        long recordsRead = 0;
        long recordsWritten = 0;
        try (RecordCursor cursor = source.open(workUnit.sourcePartition())) {
            while (cursor.hasNext()) {
                LogicalRecord sourceRecord = cursor.next();
                recordsRead++;
                buffer.add(mappingEngine.map(sourceRecord, plan.mappings(), plan.nullPolicy(), plan.unmappedPolicy()));
                if (buffer.size() == batchSize) {
                    recordsWritten += flush(target, buffer);
                }
            }
            if (!buffer.isEmpty()) {
                recordsWritten += flush(target, buffer);
            }
        }
        return new WorkUnitResult(workUnit.migrationId(), workUnit.workUnitId(), recordsRead, recordsWritten);
    }

    private int flush(TargetConnector target, List<LogicalRecord> buffer) {
        int count = buffer.size();
        target.writeBatch(List.copyOf(buffer));
        buffer.clear();
        return count;
    }
}
