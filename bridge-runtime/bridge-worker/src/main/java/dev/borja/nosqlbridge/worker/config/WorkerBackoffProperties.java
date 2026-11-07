package dev.borja.nosqlbridge.worker.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("bridge.worker.backoff")
public record WorkerBackoffProperties(Duration initialDelay, Duration maxDelay, Double jitterRatio,
        Duration stackTraceInterval) {
    public WorkerBackoffProperties {
        initialDelay = initialDelay == null ? Duration.ofSeconds(1) : initialDelay;
        maxDelay = maxDelay == null ? Duration.ofSeconds(30) : maxDelay;
        jitterRatio = jitterRatio == null ? 0.5 : jitterRatio;
        stackTraceInterval = stackTraceInterval == null ? Duration.ofMinutes(1) : stackTraceInterval;
        if (initialDelay.compareTo(Duration.ofMillis(1)) < 0 || maxDelay.compareTo(initialDelay) < 0
                || maxDelay.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Worker backoff requires 1ms <= initialDelay <= maxDelay <= 1 day.");
        }
        if (!Double.isFinite(jitterRatio) || jitterRatio < 0 || jitterRatio > 1) {
            throw new IllegalArgumentException("Worker backoff jitterRatio must be between 0 and 1.");
        }
        if (stackTraceInterval.compareTo(Duration.ofMillis(1)) < 0
                || stackTraceInterval.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Worker backoff stackTraceInterval must be between 1ms and 1 day.");
        }
    }

    public long delayMillis(int consecutiveFailures, double random) {
        if (consecutiveFailures < 1 || !Double.isFinite(random) || random < 0 || random > 1) {
            throw new IllegalArgumentException("Backoff needs a positive failure count and a random value between 0 and 1.");
        }
        long cap = initialDelay.toMillis();
        long maximum = maxDelay.toMillis();
        for (int failure = 1; failure < consecutiveFailures && cap < maximum; failure++) {
            cap = cap > maximum / 2 ? maximum : Math.min(maximum, cap * 2);
        }
        // Equal jitter by default: [cap / 2, cap]. The scheduler tick may round the wait up.
        return Math.max(1, (long) (cap * (1 - jitterRatio + jitterRatio * random)));
    }
}
