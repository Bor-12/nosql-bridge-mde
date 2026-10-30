package dev.borja.nosqlbridge.worker.mapping;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import dev.borja.nosqlbridge.common.model.LogicalValue;
import dev.borja.nosqlbridge.common.plan.MappingDefaultValue;

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

    @ParameterizedTest
    @MethodSource("integerDefaults")
    void convertsIntegerDefault(Object value, long expected) {
        LogicalValue result = converter.convert(new MappingDefaultValue("integer", value));

        assertEquals(new LogicalValue.IntegerValue(expected), result);
    }

    static Stream<Arguments> integerDefaults() {
        return Stream.of(
                Arguments.of((byte) 25, 25L),
                Arguments.of((short) 256, 256L),
                Arguments.of(25, 25L),
                Arguments.of(Long.MIN_VALUE, Long.MIN_VALUE),
                Arguments.of(BigInteger.valueOf(Long.MAX_VALUE), Long.MAX_VALUE),
                Arguments.of(new BigDecimal("25.000"), 25L),
                Arguments.of(25.0f, 25L),
                Arguments.of(25.0d, 25L));
    }

    @ParameterizedTest
    @MethodSource("decimalDefaults")
    void convertsDecimalDefault(Object value, BigDecimal expected) {
        LogicalValue result = converter.convert(new MappingDefaultValue("decimal", value));

        assertEquals(new LogicalValue.DecimalValue(expected), result);
    }

    static Stream<Arguments> decimalDefaults() {
        return Stream.of(
                Arguments.of(new BigDecimal("12.500"), new BigDecimal("12.500")),
                Arguments.of(new BigInteger("9223372036854775808"), new BigDecimal("9223372036854775808")),
                Arguments.of((byte) 12, BigDecimal.valueOf(12)),
                Arguments.of((short) 256, BigDecimal.valueOf(256)),
                Arguments.of(65536, BigDecimal.valueOf(65536)),
                Arguments.of(Long.MAX_VALUE, BigDecimal.valueOf(Long.MAX_VALUE)),
                Arguments.of(0.1f, new BigDecimal("0.10000000149011612")),
                Arguments.of(12.50, new BigDecimal("12.5")));
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

    @ParameterizedTest
    @MethodSource("inexactIntegerDefaults")
    void rejectsInexactIntegerDefault(Object value, String valueType) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> converter.convert(new MappingDefaultValue("integer", value)));

        assertEquals("Default value of type integer is incompatible with value type " + valueType + ".",
                exception.getMessage());
    }

    static Stream<Arguments> inexactIntegerDefaults() {
        return Stream.of(
                Arguments.of(new BigInteger("9223372036854775808"), "BigInteger"),
                Arguments.of(new BigDecimal("25.5"), "BigDecimal"),
                Arguments.of(new BigDecimal("9223372036854775808"), "BigDecimal"),
                Arguments.of(25.5d, "Double"),
                Arguments.of(0x1.0p63, "Double"));
    }

    @ParameterizedTest
    @MethodSource("nonFiniteDefaults")
    void rejectsNonFiniteNumericDefault(String type, double value) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> converter.convert(new MappingDefaultValue(type, value)));

        assertEquals("Default value of type numeric is incompatible with value type Double.",
                exception.getMessage());
    }

    static Stream<Arguments> nonFiniteDefaults() {
        return Stream.of(
                Arguments.of("integer", Double.NaN),
                Arguments.of("integer", Double.POSITIVE_INFINITY),
                Arguments.of("decimal", Double.NaN),
                Arguments.of("decimal", Double.POSITIVE_INFINITY));
    }

    @Test
    void rejectsNonStringDefault() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> converter.convert(new MappingDefaultValue("string", 25)));

        assertEquals("Default value of type string is incompatible with value type Integer.",
                exception.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"integer", "decimal"})
    void rejectsNumericStringDefault(String type) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> converter.convert(new MappingDefaultValue(type, "25")));

        assertEquals("Default value of type " + type + " is incompatible with value type String.",
                exception.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"string", "integer", "decimal"})
    void rejectsNullTypedDefaultValue(String type) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> converter.convert(new MappingDefaultValue(type, null)));

        assertEquals("Default value of type " + type + " is incompatible with value type null.",
                exception.getMessage());
    }

    @Test
    void rejectsNullDefaultType() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> converter.convert(new MappingDefaultValue(null, "Fallback")));

        assertEquals("Unsupported default value type: null", exception.getMessage());
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
