package dev.borja.nosqlbridge.common.queue;

import java.util.Optional;

import dev.borja.nosqlbridge.common.work.WorkUnitMessage;

public interface WorkQueue {

    void publish(WorkUnitMessage message);

    Optional<Delivery> receive();

    void acknowledge(Delivery delivery);

    /** Brokers without visibility leases can keep the default no-op implementation. */
    default Lease maintainVisibility(Delivery delivery) {
        return new Lease() {
            public void check() {}
            public void close() {}
        };
    }

    interface Lease extends AutoCloseable {
        /** Reports renewal failure, including after close has frozen the result. */
        void check();
        /** Stops renewal and waits for in-flight renewal before returning. */
        @Override void close();
    }

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
