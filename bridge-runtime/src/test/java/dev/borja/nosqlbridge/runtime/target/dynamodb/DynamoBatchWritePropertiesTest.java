package dev.borja.nosqlbridge.runtime.target.dynamodb;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynamoBatchWritePropertiesTest {

    @Test
    void suppliesDefaultsForAbsentSettings() {
        DynamoBatchWriteProperties properties = new DynamoBatchWriteProperties(null, null, null);

        assertEquals(5, properties.maxRetries());
        assertEquals(Duration.ofMillis(100), properties.initialBackoff());
        assertEquals(Duration.ofSeconds(5), properties.maxBackoff());
    }

    @Test
    void acceptsZeroRetriesAndEqualBackoffBounds() {
        DynamoBatchWriteProperties properties = new DynamoBatchWriteProperties(0, Duration.ofMillis(1), Duration.ofMillis(1));

        assertEquals(0, properties.maxRetries());
    }

    @Test
    void rejectsNegativeRetriesAndInvalidDelays() {
        assertThrows(IllegalArgumentException.class, () -> new DynamoBatchWriteProperties(-1, null, null));
        assertThrows(IllegalArgumentException.class, () -> new DynamoBatchWriteProperties(1, Duration.ZERO, null));
        assertThrows(IllegalArgumentException.class, () -> new DynamoBatchWriteProperties(1, Duration.ofNanos(1), null));
        assertThrows(IllegalArgumentException.class, () -> new DynamoBatchWriteProperties(1, Duration.ofMillis(-1), null));
        assertThrows(IllegalArgumentException.class,
                () -> new DynamoBatchWriteProperties(1, Duration.ofMillis(10), Duration.ofMillis(9)));
        assertThrows(IllegalArgumentException.class,
                () -> new DynamoBatchWriteProperties(1, null, Duration.ofSeconds(Long.MAX_VALUE)));
    }
}
