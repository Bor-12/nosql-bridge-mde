package dev.borja.nosqlbridge.worker.config;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkerBackoffPropertiesTest {
    @Test void exponentialCapAndEqualJitterHaveBoundedDeterministicLimits() {
        var properties = new WorkerBackoffProperties(null, null, null, null);
        long[] caps = {1000, 2000, 4000, 8000, 16000, 30000, 30000};
        for (int index = 0; index < caps.length; index++) {
            assertEquals(caps[index], properties.delayMillis(index + 1, 1));
            assertEquals(caps[index] / 2, properties.delayMillis(index + 1, 0));
            long middle = properties.delayMillis(index + 1, 0.3);
            assertTrue(middle >= caps[index] / 2 && middle <= caps[index]);
        }
        assertEquals(30000, properties.delayMillis(Integer.MAX_VALUE, 1));
    }

    @Test void customConfigurationSupportsSmallWaitsAndDisabledJitter() {
        var properties = new WorkerBackoffProperties(Duration.ofMillis(200), Duration.ofSeconds(2), 0.0, Duration.ofSeconds(10));
        assertEquals(200, properties.delayMillis(1, 0));
        assertEquals(400, properties.delayMillis(2, 1));
        assertEquals(2000, properties.delayMillis(30, 0));
    }

    @Test void rejectsInvalidOrOverflowingConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new WorkerBackoffProperties(Duration.ZERO, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new WorkerBackoffProperties(Duration.ofSeconds(5), Duration.ofSeconds(1), null, null));
        assertThrows(IllegalArgumentException.class, () -> new WorkerBackoffProperties(null, Duration.ofDays(2), null, null));
        assertThrows(IllegalArgumentException.class, () -> new WorkerBackoffProperties(null, null, -0.1, null));
        assertThrows(IllegalArgumentException.class, () -> new WorkerBackoffProperties(null, null, 1.1, null));
        assertThrows(IllegalArgumentException.class, () -> new WorkerBackoffProperties(null, null, Double.NaN, null));
        assertThrows(IllegalArgumentException.class, () -> new WorkerBackoffProperties(null, null, null, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new WorkerBackoffProperties(null, null, null, Duration.ofDays(2)));
        var properties = new WorkerBackoffProperties(null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> properties.delayMillis(0, 0.5));
        assertThrows(IllegalArgumentException.class, () -> properties.delayMillis(1, Double.NaN));
    }
}
