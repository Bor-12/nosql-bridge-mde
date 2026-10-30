package dev.borja.nosqlbridge.runtime.worker;

import java.util.Optional;

import dev.borja.nosqlbridge.runtime.execution.WorkUnitExecutor;
import dev.borja.nosqlbridge.runtime.queue.WorkQueue;
import dev.borja.nosqlbridge.runtime.queue.WorkQueue.Delivery;
import dev.borja.nosqlbridge.runtime.work.WorkUnitMessage;
import dev.borja.nosqlbridge.runtime.work.WorkUnitResult;

public class WorkerConsumer {

    private final WorkQueue workQueue;
    private final WorkUnitExecutor workUnitExecutor;

    public WorkerConsumer(WorkQueue workQueue, WorkUnitExecutor workUnitExecutor) {
        if (workQueue == null) {
            throw new IllegalArgumentException("Work queue must not be null.");
        }
        if (workUnitExecutor == null) {
            throw new IllegalArgumentException("Work unit executor must not be null.");
        }
        this.workQueue = workQueue;
        this.workUnitExecutor = workUnitExecutor;
    }

    public Optional<WorkUnitResult> processOne() {
        Optional<Delivery> received = workQueue.receive();
        if (received.isEmpty()) {
            return Optional.empty();
        }
        Delivery delivery = received.orElseThrow();
        WorkUnitMessage message = delivery.message();
        WorkUnitResult result = workUnitExecutor.execute(message.workUnit(), message.plan());
        workQueue.acknowledge(delivery);
        return Optional.of(result);
    }
}
