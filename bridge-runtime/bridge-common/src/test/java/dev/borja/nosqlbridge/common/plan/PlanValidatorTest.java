package dev.borja.nosqlbridge.common.plan;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlanValidatorTest {

    private PlanValidator validator;
    private ConnectorPlan source;
    private ConnectorPlan target;

    @BeforeEach
    void setUp() {
        validator = new PlanValidator();
        source = new ConnectorPlan("mongodb",
                Map.of("database", "shop", "collection", "products"));
        target = new ConnectorPlan("dynamodb",
                Map.of("table", "Products", "partitionKey", "id"));
    }

    @Test
    void acceptsSupportedVersion() {
        BridgeExecutionPlan plan = createPlan(1);

        assertDoesNotThrow(() -> validator.validate(plan));
    }

    @Test
    void rejectsUnsupportedVersion() {
        BridgeExecutionPlan plan = createPlan(2);

        UnsupportedPlanVersionException exception = assertThrows(
                UnsupportedPlanVersionException.class, () -> validator.validate(plan));
        assertEquals("Unsupported execution plan version 2. Supported version is 1.",
                exception.getMessage());
    }

    private BridgeExecutionPlan createPlan(int planVersion) {
        return new BridgeExecutionPlan(planVersion, "TestBridge", source, target,
                "preserve", "preserve", List.of());
    }

    @Test
    void rejectsNullPlan() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> validator.validate(null));
        assertEquals("Execution plan must not be null.", exception.getMessage());
    }

    @Test
    void rejectsMissingSource() {
        source = null;

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> validator.validate(createPlan(1)));
        assertEquals("Execution plan source must not be null.", exception.getMessage());
    }

    @Test
    void rejectsMissingTarget() {
        target = null;

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> validator.validate(createPlan(1)));
        assertEquals("Execution plan target must not be null.", exception.getMessage());
    }

    @Test
    void rejectsBlankSourceTechnology() {
        source = new ConnectorPlan(" ", Map.of());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> validator.validate(createPlan(1)));
        assertEquals("Execution plan source technology must not be blank.", exception.getMessage());
    }

    @Test
    void rejectsMissingTargetTechnology() {
        target = new ConnectorPlan(null, Map.of());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> validator.validate(createPlan(1)));
        assertEquals("Execution plan target technology must not be blank.", exception.getMessage());
    }

    @Test
    void acceptsOtherTechnologiesWithoutDependingOnInstalledConnectors() {
        source = new ConnectorPlan("another-source", Map.of());
        target = new ConnectorPlan("another-target", Map.of());

        assertDoesNotThrow(() -> validator.validate(createPlan(1)));
    }
}
