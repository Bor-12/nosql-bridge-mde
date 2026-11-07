package dev.borja.nosqlbridge.worker;

import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import dev.borja.nosqlbridge.common.logging.SafeLogException;
import dev.borja.nosqlbridge.common.plan.*;
import dev.borja.nosqlbridge.common.queue.WorkQueue;
import dev.borja.nosqlbridge.common.work.*;
import dev.borja.nosqlbridge.worker.execution.WorkUnitExecutor;
import dev.borja.nosqlbridge.worker.work.WorkUnitResult;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingTest {
    @Configuration(proxyBeanMethods = false)
    static class ProbeConfiguration {}

    @Test void nativeEcsJsonWorksOnTheResolvedSpringBootVersionWithCorrelationAndSafeStacks(CapturedOutput output) {
        SpringApplication application = new SpringApplication(ProbeConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setRegisterShutdownHook(false);
        application.setLogStartupInfo(false);
        application.setDefaultProperties(Map.of("logging.structured.format.console", "ecs"));
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try (var context = application.run("--logging.level.dev.borja.nosqlbridge.worker.acknowledgement=INFO")) {
            MDC.put("migrationId", "550e8400-e29b-41d4-a716-446655440000");
            LoggerFactory.getLogger(StructuredLoggingTest.class).atError()
                    .addKeyValue("eventName", "JSON_LOGGING_PROBE")
                    .addKeyValue("bootVersion", SpringBootVersion.getVersion())
                    .addKeyValue("workUnitId", 7)
                    .setCause(SafeLogException.snapshot(new IllegalArgumentException("secret-document-value")))
                    .log("JSON_LOGGING_PROBE");
            WorkQueue queue = mock(WorkQueue.class);
            WorkUnitExecutor executor = mock(WorkUnitExecutor.class);
            WorkUnit unit = new WorkUnit(new MigrationId(UUID.fromString("550e8400-e29b-41d4-a716-446655440000")),
                    new WorkUnitId(7), new SourcePartition("test", Map.of()));
            BridgeExecutionPlan plan = new BridgeExecutionPlan(1, "test", new ConnectorPlan("mongodb", Map.of()),
                    new ConnectorPlan("dynamodb", Map.of()), "drop", "omit", List.of());
            when(queue.receive()).thenReturn(Optional.of(new WorkQueue.Delivery(new WorkUnitMessage(unit, plan), "secret-receipt")))
                    .thenThrow(new IllegalStateException("secret-document-value"));
            when(executor.execute(unit, plan)).thenAnswer(invocation -> {
                MDC.put("phase", "source_close");
                MDC.put("recordsReadSoFar", "3");
                MDC.put("recordsWrittenConfirmedSoFar", "3");
                return new WorkUnitResult(unit.migrationId(), unit.workUnitId(), 3, 3);
            });
            WorkerConsumer consumer = new WorkerConsumer(queue, executor);
            consumer.processOne();
            assertThrows(IllegalStateException.class, consumer::processOne);
        } finally {
            if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
        }
        String line = output.getAll().lines().filter(s -> s.startsWith("{") && s.contains("JSON_LOGGING_PROBE")).findFirst().orElseThrow();
        var json = new ObjectMapper().readTree(line);
        assertEquals("JSON_LOGGING_PROBE", json.get("eventName").asText());
        assertEquals(SpringBootVersion.getVersion(), json.get("bootVersion").asText());
        assertEquals(7, json.get("workUnitId").asInt());
        assertEquals("550e8400-e29b-41d4-a716-446655440000", json.get("migrationId").asText());
        assertFalse(output.getAll().contains("secret-document-value"));
        assertFalse(output.getAll().contains("secret-receipt"));
        String ackLine = output.getAll().lines().filter(s -> s.startsWith("{") && s.contains("BRIDGE_WORKUNIT_ACKNOWLEDGED migrationId=")).findFirst().orElseThrow();
        var ack = new ObjectMapper().readTree(ackLine);
        assertEquals("WORKUNIT_ACKNOWLEDGED", ack.get("eventName").asText());
        assertEquals(3, ack.get("recordsRead").asInt());
        assertEquals("acknowledge", ack.get("phase").asText());
        String failureLine = output.getAll().lines().filter(s -> s.startsWith("{") && s.contains("BRIDGE_WORKER_ITERATION_FAILED migrationId=")).findFirst().orElseThrow();
        assertEquals("receive", new ObjectMapper().readTree(failureLine).get("phase").asText());
        assertFalse(output.getAll().contains("Duplicate nested pairs"));
    }
}
