package dev.borja.nosqlbridge.worker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import software.amazon.awssdk.services.sqs.model.SqsException;
import dev.borja.nosqlbridge.common.queue.WorkQueue;
import dev.borja.nosqlbridge.common.registry.TechnologyRegistry;
import dev.borja.nosqlbridge.common.work.WorkUnitMessage;
import dev.borja.nosqlbridge.worker.config.WorkerConfiguration;
import dev.borja.nosqlbridge.worker.execution.WorkUnitExecutor;
import dev.borja.nosqlbridge.worker.mapping.DefaultValueConverter;
import dev.borja.nosqlbridge.worker.mapping.MappingEngine;
import dev.borja.nosqlbridge.worker.model.LogicalPathResolver;
import dev.borja.nosqlbridge.worker.transformation.TransformationEngine;
import static org.junit.jupiter.api.Assertions.*;

class WorkerFatalShutdownTest {
    @TempDir Path directory;

    @Test void springConfiguredWorkerClosesAndExitsOneOnFatalQueueFailure() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Path log = directory.resolve("fatal-worker.log");
        Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), FatalWorker.class.getName())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Fatal worker did not exit: " + Files.readString(log));
            String output = Files.readString(log);
            assertEquals(1, process.exitValue(), output);
            assertTrue(output.contains("failureDisposition=FATAL"), output);
            assertTrue(output.contains("decisionReason=configured_queue_access_or_request_rejected"), output);
            assertTrue(output.contains("WORKER_STOPPING"), output);
            assertTrue(output.contains("PROBE_RESOURCE_CLOSED"), output);
            assertFalse(output.contains("private-credentials"));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    /** Runs the production Spring wiring with a queue stub; no broker or database is contacted. */
    public static class FatalWorker {
        public static void main(String[] args) {
            System.setProperty("bridge.runtime.mode", "worker");
            var context = new AnnotationConfigApplicationContext();
            context.registerBean("probeResource", ProbeResource.class, ProbeResource::new,
                    bean -> bean.setDestroyMethodName("close"));
            context.registerBean(WorkQueue.class, () -> new WorkQueue() {
                @Override public void publish(WorkUnitMessage message) { throw new AssertionError("Unexpected publish"); }
                @Override public Optional<Delivery> receive() {
                    throw SqsException.builder().statusCode(403).message("private-credentials").build();
                }
                @Override public void acknowledge(Delivery delivery) { throw new AssertionError("Unexpected ACK"); }
            });
            context.registerBean(WorkUnitExecutor.class, () -> new WorkUnitExecutor(
                    new TechnologyRegistry(List.of(), List.of()),
                    new MappingEngine(new LogicalPathResolver(), new TransformationEngine(), new DefaultValueConverter()), 25));
            context.register(WorkerConfiguration.class);
            context.refresh();
        }
    }

    public static class ProbeResource implements AutoCloseable {
        @Override public void close() { System.out.println("PROBE_RESOURCE_CLOSED"); }
    }
}
