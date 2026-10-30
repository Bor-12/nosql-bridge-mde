package dev.borja.nosqlbridge.common.work;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SourcePartitionTest {

    @Test
    void preservesTypeAndCopiesParameters() {
        Map<String, String> parameters = new HashMap<>(Map.of("upper", "10"));
        SourcePartition partition = new SourcePartition("test-range", parameters);

        parameters.put("upper", "20");

        assertEquals("test-range", partition.type());
        assertEquals(Map.of("upper", "10"), partition.parameters());
        assertThrows(UnsupportedOperationException.class, () -> partition.parameters().put("upper", "30"));
    }

    @Test
    void rejectsMissingTypeOrParameters() {
        assertThrows(IllegalArgumentException.class, () -> new SourcePartition(null, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new SourcePartition("", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new SourcePartition(" \t", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new SourcePartition("test-range", null));
    }
}
