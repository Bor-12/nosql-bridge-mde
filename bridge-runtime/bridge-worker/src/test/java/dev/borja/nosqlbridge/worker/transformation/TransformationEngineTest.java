package dev.borja.nosqlbridge.worker.transformation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.borja.nosqlbridge.common.model.LogicalValue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransformationEngineTest {

    private TransformationEngine engine;

    @BeforeEach
    void setUp() {
        engine = new TransformationEngine();
    }

    @Test
    void preservesValueWhenTransformationIsNull() {
        LogicalValue.IntegerValue value = new LogicalValue.IntegerValue(10);

        assertSame(value, engine.transform(null, value));
    }

    @Test
    void preservesLogicalNull() {
        LogicalValue.NullValue value = new LogicalValue.NullValue();

        assertSame(value, engine.transform("toString", value));
        assertSame(value, engine.transform("toInteger", value));
        assertSame(value, engine.transform("toDecimal", value));
    }

    @Test
    void convertsStringToString() {
        LogicalValue.StringValue value = new LogicalValue.StringValue("Product");

        assertSame(value, engine.transform("toString", value));
    }

    @Test
    void convertsIntegerToString() {
        LogicalValue.IntegerValue value = new LogicalValue.IntegerValue(10);

        assertEquals(new LogicalValue.StringValue("10"), engine.transform("toString", value));
    }

    @Test
    void convertsDecimalToString() {
        LogicalValue.DecimalValue value = new LogicalValue.DecimalValue(new BigDecimal("12.50"));

        assertEquals(new LogicalValue.StringValue("12.50"), engine.transform("toString", value));
    }

    @Test
    void convertsBooleanToString() {
        LogicalValue.BooleanValue value = new LogicalValue.BooleanValue(true);

        assertEquals(new LogicalValue.StringValue("true"), engine.transform("toString", value));
    }

    @Test
    void convertsBinaryToBase64String() {
        LogicalValue.BinaryValue value = new LogicalValue.BinaryValue(new byte[] {1, 2, 3});

        assertEquals(new LogicalValue.StringValue("AQID"), engine.transform("toString", value));
    }

    @Test
    void convertsTimestampToIsoString() {
        LogicalValue.TimestampValue value = new LogicalValue.TimestampValue(
                Instant.parse("2026-10-10T10:00:00Z"));

        assertEquals(new LogicalValue.StringValue("2026-10-10T10:00:00Z"),
                engine.transform("toString", value));
    }

    @Test
    void convertsStringToInteger() {
        LogicalValue.StringValue value = new LogicalValue.StringValue("25");

        assertEquals(new LogicalValue.IntegerValue(25), engine.transform("toInteger", value));
    }

    @Test
    void preservesIntegerWhenConvertingToInteger() {
        LogicalValue.IntegerValue value = new LogicalValue.IntegerValue(10);

        assertSame(value, engine.transform("toInteger", value));
    }

    @Test
    void convertsExactDecimalToInteger() {
        LogicalValue.DecimalValue value = new LogicalValue.DecimalValue(new BigDecimal("10.00"));

        assertEquals(new LogicalValue.IntegerValue(10), engine.transform("toInteger", value));
    }

    @Test
    void convertsStringToDecimal() {
        LogicalValue.StringValue value = new LogicalValue.StringValue("12.50");

        assertEquals(new LogicalValue.DecimalValue(new BigDecimal("12.50")),
                engine.transform("toDecimal", value));
    }

    @Test
    void convertsIntegerToDecimal() {
        LogicalValue.IntegerValue value = new LogicalValue.IntegerValue(10);

        assertEquals(new LogicalValue.DecimalValue(BigDecimal.valueOf(10)),
                engine.transform("toDecimal", value));
    }

    @Test
    void preservesDecimalWhenConvertingToDecimal() {
        LogicalValue.DecimalValue value = new LogicalValue.DecimalValue(new BigDecimal("12.50"));

        assertSame(value, engine.transform("toDecimal", value));
    }

    @Test
    void rejectsUnknownTransformation() {
        LogicalValue.IntegerValue value = new LogicalValue.IntegerValue(10);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> engine.transform("pepe", value));
        assertTrue(exception.getMessage().contains("pepe"));
    }

    @Test
    void rejectsBooleanToInteger() {
        LogicalValue.BooleanValue value = new LogicalValue.BooleanValue(true);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> engine.transform("toInteger", value));
        assertTrue(exception.getMessage().contains("toInteger"));
        assertTrue(exception.getMessage().contains("BooleanValue"));
    }

    @Test
    void rejectsObjectToString() {
        LogicalValue.ObjectValue value = new LogicalValue.ObjectValue(Map.of(
                "name", new LogicalValue.StringValue("Product")));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> engine.transform("toString", value));

        assertEquals("Transformation toString does not support ObjectValue.", exception.getMessage());
    }

    @Test
    void rejectsBooleanToDecimal() {
        LogicalValue.BooleanValue value = new LogicalValue.BooleanValue(true);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> engine.transform("toDecimal", value));

        assertEquals("Transformation toDecimal does not support BooleanValue.", exception.getMessage());
    }

    @Test
    void rejectsNonNumericStringToInteger() {
        LogicalValue.StringValue value = new LogicalValue.StringValue("Product");

        assertThrows(NumberFormatException.class, () -> engine.transform("toInteger", value));
    }

    @Test
    void rejectsNonNumericStringToDecimal() {
        LogicalValue.StringValue value = new LogicalValue.StringValue("Product");

        assertThrows(NumberFormatException.class, () -> engine.transform("toDecimal", value));
    }

    @Test
    void rejectsFractionalDecimalToInteger() {
        LogicalValue.DecimalValue value = new LogicalValue.DecimalValue(new BigDecimal("10.50"));

        assertThrows(ArithmeticException.class, () -> engine.transform("toInteger", value));
    }

    @Test
    void rejectsDecimalOutsideLongRange() {
        LogicalValue.DecimalValue value = new LogicalValue.DecimalValue(
                new BigDecimal("9223372036854775808"));

        assertThrows(ArithmeticException.class, () -> engine.transform("toInteger", value));
    }
}
