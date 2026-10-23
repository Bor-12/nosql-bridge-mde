package dev.borja.nosqlbridge.runtime.orchestration;

import dev.borja.nosqlbridge.runtime.mapping.MappingEngine;
import dev.borja.nosqlbridge.runtime.model.LogicalRecord;
import dev.borja.nosqlbridge.runtime.plan.BridgeExecutionPlan;
import dev.borja.nosqlbridge.runtime.source.connector.RecordCursor;
import dev.borja.nosqlbridge.runtime.source.connector.SourceConnector;
import dev.borja.nosqlbridge.runtime.target.TargetConnector;

public class BridgeOrchestrator {

    private final SourceConnector sourceConnector;
    private final TargetConnector targetConnector;
    private final MappingEngine mappingEngine;

    public BridgeOrchestrator(
            SourceConnector sourceConnector,
            TargetConnector targetConnector,
            MappingEngine mappingEngine) {
        this.sourceConnector = sourceConnector;
        this.targetConnector = targetConnector;
        this.mappingEngine = mappingEngine;
    }

    public void execute(BridgeExecutionPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("Execution plan must not be null");
        }

        try (RecordCursor cursor = sourceConnector.open()) {
            while (cursor.hasNext()) {
                LogicalRecord sourceRecord = cursor.next();
                LogicalRecord mappedRecord = mappingEngine.map(
                        sourceRecord, plan.mappings(), plan.nullPolicy(), plan.unmappedPolicy());
                targetConnector.write(mappedRecord);
            }
        }
    }
}
