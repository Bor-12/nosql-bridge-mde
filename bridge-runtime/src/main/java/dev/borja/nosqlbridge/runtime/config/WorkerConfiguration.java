package dev.borja.nosqlbridge.runtime.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import dev.borja.nosqlbridge.runtime.execution.WorkUnitExecutor;
import dev.borja.nosqlbridge.runtime.queue.WorkQueue;
import dev.borja.nosqlbridge.runtime.worker.WorkerConsumer;
import dev.borja.nosqlbridge.runtime.worker.WorkerPollingTask;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "bridge.runtime.mode", havingValue = "worker")
@EnableScheduling
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
    public WorkerPollingTask workerPollingTask(WorkerConsumer consumer) {
        return new WorkerPollingTask(consumer);
    }

    @Bean
    public ThreadPoolTaskScheduler workerTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("bridge-worker-");
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }
}
