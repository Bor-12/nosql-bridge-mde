package dev.borja.nosqlbridge.runtime.plan;

public class PlanValidator {

    private static final int SUPPORTED_PLAN_VERSION = 1;

    public void validate(BridgeExecutionPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("Execution plan must not be null.");
        }
        if (plan.planVersion() != SUPPORTED_PLAN_VERSION) {
            throw new UnsupportedPlanVersionException(plan.planVersion(), SUPPORTED_PLAN_VERSION);
        }
        if (plan.source() == null) {
            throw new IllegalArgumentException("Execution plan source must not be null.");
        }
        if (plan.target() == null) {
            throw new IllegalArgumentException("Execution plan target must not be null.");
        }
        if (plan.source().technology() == null || plan.source().technology().isBlank()) {
            throw new IllegalArgumentException("Execution plan source technology must not be blank.");
        }
        if (plan.target().technology() == null || plan.target().technology().isBlank()) {
            throw new IllegalArgumentException("Execution plan target technology must not be blank.");
        }
    }
}
