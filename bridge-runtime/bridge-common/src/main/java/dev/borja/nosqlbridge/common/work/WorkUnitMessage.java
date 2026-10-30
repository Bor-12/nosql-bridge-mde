package dev.borja.nosqlbridge.common.work;

import dev.borja.nosqlbridge.common.plan.BridgeExecutionPlan;

public record WorkUnitMessage(WorkUnit workUnit, BridgeExecutionPlan plan) {

    public WorkUnitMessage {
        if (workUnit == null) {
            throw new IllegalArgumentException("Work unit must not be null.");
        }
        if (plan == null) {
            throw new IllegalArgumentException("Execution plan must not be null.");
        }
    }
}
