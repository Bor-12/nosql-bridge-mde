package dev.borja.nosqlbridge.runtime.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;

public class WorkerPollingTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerPollingTask.class);

    private final WorkerConsumer consumer;

    public WorkerPollingTask(WorkerConsumer consumer) {
        if (consumer == null) {
            throw new IllegalArgumentException("Worker consumer must not be null.");
        }
        this.consumer = consumer;
    }

    @Scheduled(fixedDelay = 1000, scheduler = "workerTaskScheduler")
    public void poll() {
        try {
            consumer.processOne();
        } catch (RuntimeException failure) {
            LOGGER.error("Worker iteration failed with {}. The next attempt follows the scheduled delay.",
                    failure.getClass().getName());
        }
    }

    @EventListener(ContextClosedEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void stop() {
        consumer.stopReceiving();
    }
}
