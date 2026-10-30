package dev.borja.nosqlbridge.common.queue;

import java.util.Optional;

import dev.borja.nosqlbridge.common.work.WorkUnitMessage;

public interface WorkQueue {

    void publish(WorkUnitMessage message);

    Optional<Delivery> receive();

    void acknowledge(Delivery delivery);

    record Delivery(WorkUnitMessage message, String acknowledgementToken) {

        public Delivery {
            if (message == null) {
                throw new IllegalArgumentException("Work unit message must not be null.");
            }
            if (acknowledgementToken == null || acknowledgementToken.isBlank()) {
                throw new IllegalArgumentException("Acknowledgement token must not be blank.");
            }
        }
    }
}
