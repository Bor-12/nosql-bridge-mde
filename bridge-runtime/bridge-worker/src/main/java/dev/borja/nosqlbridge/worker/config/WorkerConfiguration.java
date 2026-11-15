package dev.borja.nosqlbridge.worker.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import dev.borja.nosqlbridge.worker.execution.WorkUnitExecutor;
import dev.borja.nosqlbridge.common.queue.WorkQueue;
import dev.borja.nosqlbridge.common.config.CommonConfiguration;
import dev.borja.nosqlbridge.common.registry.TechnologyRegistry;
import dev.borja.nosqlbridge.worker.mapping.DefaultValueConverter;
import dev.borja.nosqlbridge.worker.mapping.MappingEngine;
import dev.borja.nosqlbridge.worker.mapping.LogicalPathResolver;
import dev.borja.nosqlbridge.worker.transformation.TransformationEngine;
import dev.borja.nosqlbridge.worker.consumer.WorkerConsumer;
import dev.borja.nosqlbridge.worker.consumer.WorkerPollingTask;

@Configuration(proxyBeanMethods = false)
@Import(CommonConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties(WorkerBackoffProperties.class)
public class WorkerConfiguration {

    @Bean
    public MappingEngine mappingEngine() {
        return new MappingEngine(new LogicalPathResolver(), new TransformationEngine(), new DefaultValueConverter());
    }

    @Bean
    public WorkUnitExecutor workUnitExecutor(TechnologyRegistry technologyRegistry, MappingEngine mappingEngine,
            @Value("${bridge.execution.batch-size:25}") int batchSize) {
        return new WorkUnitExecutor(technologyRegistry, mappingEngine, batchSize);
    }

    @Bean
    public WorkerConsumer workerConsumer(ObjectProvider<WorkQueue> queues, WorkUnitExecutor executor) {
        WorkQueue queue = queues.getIfAvailable();
        if (queue == null) {
            throw new IllegalStateException("Worker mode requires bridge.sqs.queue-url or a WorkQueue bean.");
        }
        return new WorkerConsumer(queue, executor);
    }

    @Bean
    public WorkerPollingTask workerPollingTask(WorkerConsumer consumer, WorkerBackoffProperties backoff,
            ConfigurableApplicationContext context) {
        return new WorkerPollingTask(consumer, backoff, () -> Thread.ofPlatform()
                .name("bridge-worker-fatal-shutdown").start(() -> {
                    // Closing on the scheduler's own thread can wait for itself. Exit from a separate thread.
                    try { SpringApplication.exit(context, () -> 1); }
                    finally { System.exit(1); }
                }));
    }

    @Bean
    public ThreadPoolTaskScheduler workerTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("bridge-worker-");
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        // Keep Spring's coordinated lifecycle stop: active execution/ACK finishes before beans close.
        // After its bounded shutdown phase expires, await interrupted task termination as well.
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }
}
