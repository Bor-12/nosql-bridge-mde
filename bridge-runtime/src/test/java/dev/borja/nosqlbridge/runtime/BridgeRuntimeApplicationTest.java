package dev.borja.nosqlbridge.runtime;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;

import dev.borja.nosqlbridge.runtime.config.ExecutionConfiguration;
import dev.borja.nosqlbridge.runtime.connector.RecordCursor;
import dev.borja.nosqlbridge.runtime.connector.SourceConnector;
import dev.borja.nosqlbridge.runtime.connector.SourceConnectorFactory;
import dev.borja.nosqlbridge.runtime.connector.TargetConnector;
import dev.borja.nosqlbridge.runtime.connector.TargetConnectorFactory;
import dev.borja.nosqlbridge.runtime.plan.ConnectorPlan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BridgeRuntimeApplicationTest {

    @TempDir
    Path directory;

    private final ByteArrayOutputStream errors = new ByteArrayOutputStream();
    private final PrintStream error = new PrintStream(errors, true, StandardCharsets.UTF_8);
    private final SourceConnector source = mock(SourceConnector.class);
    private final TargetConnector target = mock(TargetConnector.class);

    static Stream<Arguments> invalidArguments() {
        return List.of(new String[] {}, new String[] { "--plan" },
                new String[] { "--plan", "" }, new String[] { "--plan", "   " },
                new String[] { "--plan", "--other" },
                new String[] { "--bridge.execution.plan=plan.json" },
                new String[] { "--plan", "plan.json", "--plan", "other.json" },
                new String[] { "--unknown", "plan.json" },
                new String[] { "--plan", "invalid\u0000path" })
                .stream().map(args -> Arguments.of((Object) args));
    }

    @ParameterizedTest
    @MethodSource("invalidArguments")
    void rejectsInvalidArgumentsBeforeStartingSpring(String[] args) {
        SpringApplication application = mock(SpringApplication.class);

        assertEquals(1, BridgeRuntimeApplication.run(application, error, args));

        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("Usage:"));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("--plan <path>"));
        verifyNoInteractions(application);
    }

    @Test
    void acceptsPlanPathWithSpacesAndExecutesUsingRealRunner() throws Exception {
        Path plan = fixture();
        RecordCursor cursor = mock(RecordCursor.class);
        when(source.open()).thenReturn(cursor);

        assertEquals(0, runPlan(plan));

        assertEquals("", errors.toString(StandardCharsets.UTF_8));
        verify(source).open();
        verify(cursor).close();
    }

    @Test
    void rejectsMissingFileBeforeOpeningConnectors() {
        Path plan = directory.resolve("missing.json");

        assertEquals(1, runPlan(plan));

        assertTrue(errors.toString(StandardCharsets.UTF_8).contains(plan.toString()));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("Plan file does not exist."));
        verifyNoInteractions(source, target);
    }

    @Test
    void rejectsUnreadableJsonBeforeOpeningConnectors() throws Exception {
        Path plan = directory.resolve("broken.json");
        Files.writeString(plan, "{ invalid JSON");

        assertEquals(1, runPlan(plan));

        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("Failed to execute plan"));
        verifyNoInteractions(source, target);
    }

    @Test
    void rejectsInvalidPlanBeforeOpeningConnectors() throws Exception {
        Path plan = fixture();
        Files.writeString(plan, Files.readString(plan).replace("\"planVersion\": 1", "\"planVersion\": 2"));

        assertEquals(1, runPlan(plan));

        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("Unsupported"));
        verifyNoInteractions(source, target);
    }

    private Path fixture() throws Exception {
        Path plan = directory.resolve("valid plan.json");
        try (var input = getClass().getResourceAsStream("/plans/custom-connectors-test.json")) {
            Files.copy(input, plan);
        }
        return plan;
    }

    private int runPlan(Path plan) {
        SpringApplication application = new SpringApplication(ExecutionConfiguration.class);
        application.addInitializers(context -> {
            context.getBeanFactory().registerSingleton("sourceFactory", new SourceConnectorFactory() {
                public String technology() { return "custom-source"; }
                public SourceConnector create(ConnectorPlan plan) { return source; }
            });
            context.getBeanFactory().registerSingleton("targetFactory", new TargetConnectorFactory() {
                public String technology() { return "custom-target"; }
                public TargetConnector create(ConnectorPlan plan) { return target; }
            });
        });
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        return BridgeRuntimeApplication.run(application, error, "--plan", plan.toString());
    }

}
