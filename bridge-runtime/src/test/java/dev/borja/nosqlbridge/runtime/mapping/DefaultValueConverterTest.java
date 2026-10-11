package dev.borja.nosqlbridge.runtime.mapping;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.borja.nosqlbridge.runtime.model.LogicalValue;
import dev.borja.nosqlbridge.runtime.plan.MappingDefaultValue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultValueConverterTest {

    private DefaultValueConverter converter;

    @BeforeEach
    void setUp() {
        converter = new DefaultValueConverter();
    }

    @Test
    void convertsStringDefault() {
        LogicalValue result = converter.convert(new MappingDefaultValue("string", "Fallback"));

        assertEquals(new LogicalValue.StringValue("Fallback"), result);
    }

    @Test
    void convertsIntegerDefault() {
        LogicalValue result = converter.convert(new MappingDefaultValue("integer", 25));

        assertEquals(new LogicalValue.IntegerValue(25), result);
    }

    @Test
    void convertsDecimalDefault() {
        LogicalValue result = converter.convert(new MappingDefaultValue("decimal", 12.50));

        assertEquals(new LogicalValue.DecimalValue(BigDecimal.valueOf(12.50)), result);
    }

    @Test
    void convertsBooleanDefault() {
        LogicalValue result = converter.convert(new MappingDefaultValue("boolean", true));

        assertEquals(new LogicalValue.BooleanValue(true), result);
    }

    @Test
    void rejectsUnsupportedDefaultType() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> converter.convert(new MappingDefaultValue("timestamp", "2026-10-11T00:00:00Z")));

        assertEquals("Unsupported default value type: timestamp", exception.getMessage());
    }

    @Test
    void rejectsIncompatibleValueType() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> converter.convert(new MappingDefaultValue("boolean", "true")));

        assertEquals("Default value of type boolean is incompatible with value type String.",
                exception.getMessage());
    }

    @Test
    void rejectsFractionalIntegerDefault() {
        assertThrows(IllegalArgumentException.class,
                () -> converter.convert(new MappingDefaultValue("integer", 25.5)));
    }

    @Test
    void rejectsNullDefaultValue() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> converter.convert(null));

        assertEquals(
                "Default value must not be null.",
                exception.getMessage());
    }
}
