package dev.borja.nosqlbridge.worker.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import dev.borja.nosqlbridge.worker.execution.WorkUnitExecutor;
import dev.borja.nosqlbridge.common.queue.WorkQueue;
import dev.borja.nosqlbridge.worker.WorkerConsumer;
import dev.borja.nosqlbridge.worker.WorkerPollingTask;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "bridge.runtime.mode", havingValue = "worker")
@EnableScheduling
@EnableConfigurationProperties(WorkerBackoffProperties.class)
public class WorkerConfiguration {

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
