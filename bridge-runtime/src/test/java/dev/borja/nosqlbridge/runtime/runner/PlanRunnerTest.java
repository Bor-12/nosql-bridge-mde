package dev.borja.nosqlbridge.runtime.runner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import dev.borja.nosqlbridge.runtime.config.ExecutionConfiguration;
import dev.borja.nosqlbridge.runtime.model.LogicalRecord;
import dev.borja.nosqlbridge.runtime.model.LogicalValue.StringValue;
import dev.borja.nosqlbridge.runtime.plan.ConnectorPlan;
import dev.borja.nosqlbridge.runtime.source.SourceTechnologyFactory;
import dev.borja.nosqlbridge.runtime.source.connector.RecordCursor;
import dev.borja.nosqlbridge.runtime.source.connector.SourceConnector;
import dev.borja.nosqlbridge.runtime.target.TargetConnector;
import dev.borja.nosqlbridge.runtime.target.TargetConnectorFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanRunnerTest {
    @TempDir
    Path directory;

    @Test
    void executesNewTechnologyUsingOnlyRegisteredFactories() throws Exception {
        List<LogicalRecord> written = new ArrayList<>();
        boolean[] closed = { false };
        SourceConnector source = () -> new RecordCursor() {
            private boolean available = true;

            public boolean hasNext() { return available; }
            public LogicalRecord next() {
                available = false;
                return new LogicalRecord(Map.of("name", new StringValue("Product")));
            }
            public void close() { closed[0] = true; }
        };

        context("custom-target", source, written::add).run(context -> {
            assertNull(context.getStartupFailure());
            context.getBean(PlanRunner.class).run();
            assertEquals(List.of(new LogicalRecord(Map.of("title", new StringValue("Product")))), written);
            assertTrue(closed[0]);
        });
    }

    @Test
    void rejectsUnknownTargetBeforeCreatingSource() throws Exception {
        context("unknown", () -> { throw new AssertionError("Source must not open."); },
                record -> { throw new AssertionError("Target must not write."); }).run(context -> {
                    assertNull(context.getStartupFailure());
                    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                            () -> context.getBean(PlanRunner.class).run());
                    assertEquals("Unsupported target technology: unknown", failure.getMessage());
                });
    }

    private ApplicationContextRunner context(String targetTechnology, SourceConnector source,
            TargetConnector target) throws Exception {
        var resource = getClass().getResource("/plans/custom-connectors-test.json");
        assertNotNull(resource, "Runner plan fixture must be available on the test classpath.");
        Path plan = Path.of(resource.toURI());
        if (!"custom-target".equals(targetTechnology)) {
            Path modifiedPlan = directory.resolve("plan.json");
            Files.writeString(modifiedPlan, Files.readString(plan)
                    .replace("\"custom-target\"", "\"" + targetTechnology + "\""));
            plan = modifiedPlan;
        }
        return new ApplicationContextRunner().withUserConfiguration(ExecutionConfiguration.class)
                .withPropertyValues("bridge.execution.plan=" + plan)
                .withBean(SourceTechnologyFactory.class, () -> new SourceTechnologyFactory() {
                    public String technology() { return "custom-source"; }
                    public SourceConnector createConnector(ConnectorPlan config) {
                        assertEquals("custom-source", config.technology());
                        if (targetTechnology.equals("unknown")) {
                            throw new AssertionError("Resolve target support before creating source.");
                        }
                        return source;
                    }
                })
                .withBean(TargetConnectorFactory.class, () -> new TargetConnectorFactory() {
                    public String technology() { return "custom-target"; }
                    public TargetConnector create(ConnectorPlan config) {
                        assertEquals("custom-target", config.technology());
                        return target;
                    }
                });
    }
}
