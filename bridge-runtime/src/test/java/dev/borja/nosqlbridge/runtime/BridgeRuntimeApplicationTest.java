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
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import dev.borja.nosqlbridge.runtime.connector.mongodb.MongoProperties;
import dev.borja.nosqlbridge.runtime.connector.dynamodb.DynamoProperties;

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
    void validatesBothFilesDuplicatesAndEmptyOptionsBeforeStartingSpring() throws Exception {
        Path plan = fixture();
        Path config = directory.resolve("bridge.yml");
        Files.writeString(config, "bridge: {}\n");
        for (String[] args : List.of(
                new String[] {"--plan", plan.toString()},
                new String[] {"--config", config.toString()},
                new String[] {"--plan", plan.toString(), "--config", ""},
                new String[] {"--plan", plan.toString(), "--config", "   "},
                new String[] {"--plan", plan.toString(), "--config", "missing.yml"},
                new String[] {"--plan", plan.toString(), "--config", directory.toString()},
                new String[] {"--plan", directory.toString(), "--config", config.toString()},
                new String[] {"--plan", plan.toString(), "--config", config.toString(), "--config", config.toString()},
                new String[] {"--plan", plan.toString(), "--config", config.toString(), "--unknown", "x"})) {
            SpringApplication application = mock(SpringApplication.class);
            assertEquals(1, BridgeRuntimeApplication.run(application, error, args));
            verifyNoInteractions(application);
        }
    }

    @Test
    void translatesEitherArgumentOrderAndClosesSpringContext() throws Exception {
        Path plan = fixture();
        Path config = directory.resolve("bridge config.yml");
        Files.writeString(config, "bridge: {}\n");
        for (String[] args : List.of(
                new String[] {"--plan", plan.toString(), "--config", config.toString()},
                new String[] {"--config", config.toString(), "--plan", plan.toString()})) {
            var application = mock(SpringApplication.class);
            var context = mock(ConfigurableApplicationContext.class);
            when(application.run("--bridge.execution.plan=" + plan,
                    "--spring.config.location=optional:classpath:/application.yaml," + config.toUri())).thenReturn(context);
            assertEquals(0, BridgeRuntimeApplication.run(application, error, args));
            verify(context).close();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({MongoProperties.class, DynamoProperties.class})
    static class PropertiesConfiguration {}

    @Test
    void loadsExternalYamlWithSpacesUsingRealSpringAndReportsMissingRequiredProperty() throws Exception {
        Path plan = fixture();
        Path config = directory.resolve("external config.yml");
        Files.writeString(config, "bridge:\n  mongodb:\n    uri: mongodb://localhost:27019\n  dynamodb:\n    region: eu-west-1\n");
        var application = new SpringApplication(PropertiesConfiguration.class);
        application.addInitializers(context -> context.addApplicationListener(event -> {
            if (event instanceof org.springframework.context.event.ContextRefreshedEvent) {
                assertEquals("mongodb://localhost:27019", context.getBean(MongoProperties.class).uri());
                assertEquals("eu-west-1", context.getBean(DynamoProperties.class).region());
                assertEquals(null, context.getBean(DynamoProperties.class).endpoint());
            }
        }));
        assertEquals(0, BridgeRuntimeApplication.run(application, error, "--plan", plan.toString(), "--config", config.toString()));
        Files.writeString(config, "bridge:\n  dynamodb:\n    region: eu-west-1\n");
        assertEquals(1, BridgeRuntimeApplication.run(new SpringApplication(PropertiesConfiguration.class), error,
                "--plan", plan.toString(), "--config", config.toString()));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("bridge.mongodb"));
    }

    @Test
    void reportsSpringFailureAndReturnsOne() throws Exception {
        Path plan = fixture();
        Path config = directory.resolve("bridge.yml");
        Files.writeString(config, "bridge: {}\n");
        var application = mock(SpringApplication.class);
        when(application.run("--bridge.execution.plan=" + plan,
                "--spring.config.location=optional:classpath:/application.yaml," + config.toUri()))
                .thenThrow(new IllegalStateException("Invalid infrastructure configuration"));
        assertEquals(1, BridgeRuntimeApplication.run(application, error, "--plan", plan.toString(), "--config", config.toString()));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("Invalid infrastructure configuration"));
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
    void rejectsMissingFileBeforeOpeningConnectors() throws Exception {
        Path plan = directory.resolve("missing.json");

        assertEquals(1, runPlan(plan));

        assertTrue(errors.toString(StandardCharsets.UTF_8).contains(plan.toString()));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("Plan file does not exist"));
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

    private int runPlan(Path plan) throws Exception {
        Path config = directory.resolve("bridge config.yml");
        Files.writeString(config, "bridge: {}\n");
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
        return BridgeRuntimeApplication.run(application, error, "--config", config.toString(), "--plan", plan.toString());
    }

}
