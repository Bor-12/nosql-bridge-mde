package dev.borja.nosqlbridge.common.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LogicalValueTest {

    @Test
    void representsLogicalValues() {
        LogicalValue.StringValue string = new LogicalValue.StringValue("Product");
        LogicalValue.IntegerValue integer = new LogicalValue.IntegerValue(5_000_000_000L);
        BigDecimal amount = new BigDecimal("12.50");
        LogicalValue.DecimalValue decimal = new LogicalValue.DecimalValue(amount);
        LogicalValue.BooleanValue bool = new LogicalValue.BooleanValue(true);
        Instant instant = Instant.parse("2026-10-10T10:00:00Z");
        LogicalValue.TimestampValue timestamp = new LogicalValue.TimestampValue(instant);
        LogicalValue nullValue = new LogicalValue.NullValue();

        assertEquals("Product", string.value());
        assertEquals(5_000_000_000L, integer.value());
        assertEquals(amount, decimal.value());
        assertEquals(true, bool.value());
        assertEquals(instant, timestamp.value());
        assertEquals(new LogicalValue.NullValue(), nullValue);
    }

    @Test
    void copiesBinaryInput() {
        byte[] bytes = {1, 2, 3};
        LogicalValue.BinaryValue binary = new LogicalValue.BinaryValue(bytes);

        bytes[0] = 9;

        assertArrayEquals(new byte[] {1, 2, 3}, binary.value());
    }

    @Test
    void copiesBinaryOutput() {
        LogicalValue.BinaryValue binary = new LogicalValue.BinaryValue(new byte[] {1, 2, 3});
        byte[] bytes = binary.value();

        bytes[0] = 9;

        assertArrayEquals(new byte[] {1, 2, 3}, binary.value());
    }

    @Test
    void storesObjectWithDefensiveCopyAndUnmodifiableValue() {
        Map<String, LogicalValue> expected = Map.of(
                "name", new LogicalValue.StringValue("Product"),
                "stock", new LogicalValue.IntegerValue(10));
        Map<String, LogicalValue> original = new HashMap<>(expected);
        LogicalValue.ObjectValue object = new LogicalValue.ObjectValue(original);

        assertEquals(expected, object.value());

        original.put("name", new LogicalValue.StringValue("Changed"));
        original.put("active", new LogicalValue.BooleanValue(true));

        assertEquals(expected, object.value());
        assertThrows(UnsupportedOperationException.class,
                () -> object.value().put("name", new LogicalValue.StringValue("Changed")));
        assertEquals(expected, object.value());
    }

    @Test
    void storesArrayWithDefensiveCopyAndUnmodifiableValue() {
        List<LogicalValue> expected = List.of(
                new LogicalValue.StringValue("Product"), new LogicalValue.IntegerValue(10));
        List<LogicalValue> original = new ArrayList<>(expected);
        LogicalValue.ArrayValue array = new LogicalValue.ArrayValue(original);

        assertEquals(expected, array.value());

        original.set(0, new LogicalValue.StringValue("Changed"));
        original.add(new LogicalValue.BooleanValue(true));

        assertEquals(expected, array.value());
        assertThrows(UnsupportedOperationException.class,
                () -> array.value().add(new LogicalValue.BooleanValue(true)));
        assertEquals(expected, array.value());
    }
}
