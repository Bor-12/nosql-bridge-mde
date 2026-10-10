package dev.borja.nosqlbridge.runtime.plan;

public class PlanValidator {

    private static final int SUPPORTED_PLAN_VERSION = 1;

    public void validate(BridgeExecutionPlan plan) {
        if (plan.planVersion() != SUPPORTED_PLAN_VERSION) {
            throw new UnsupportedPlanVersionException(plan.planVersion(), SUPPORTED_PLAN_VERSION);
        }
    }
}
