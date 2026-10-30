package dev.borja.nosqlbridge.dynamodb;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("bridge.dynamodb.batch-write")
public record DynamoBatchWriteProperties(Integer maxRetries, Duration initialBackoff, Duration maxBackoff) {

    public DynamoBatchWriteProperties {
        maxRetries = maxRetries == null ? 5 : maxRetries;
        initialBackoff = initialBackoff == null ? Duration.ofMillis(100) : initialBackoff;
        maxBackoff = maxBackoff == null ? Duration.ofSeconds(5) : maxBackoff;
        if (maxRetries < 0) {
            throw new IllegalArgumentException("DynamoDB batch maxRetries must not be negative.");
        }
        try {
            if (initialBackoff.isNegative() || initialBackoff.toMillis() < 1
                    || maxBackoff.compareTo(initialBackoff) < 0) {
                throw new IllegalArgumentException("DynamoDB batch backoff must be at least 1 ms and maxBackoff >= initialBackoff.");
            }
            maxBackoff.toMillis();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("DynamoDB batch backoff is too large.", exception);
        }
    }
}
