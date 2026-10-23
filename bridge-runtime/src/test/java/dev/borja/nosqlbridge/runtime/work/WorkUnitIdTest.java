package dev.borja.nosqlbridge.runtime.work;

import java.util.HashMap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkUnitIdTest {

    @Test
    void acceptsZeroAndPositiveValues() {
        assertEquals(0, new WorkUnitId(0).value());
        assertEquals(10, new WorkUnitId(10).value());
    }

    @Test
    void rejectsNegativeValue() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorkUnitId(-1));
    }
}
