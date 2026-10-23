package dev.borja.nosqlbridge.runtime.target.dynamodb;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import dev.borja.nosqlbridge.runtime.model.LogicalRecord;
import dev.borja.nosqlbridge.runtime.model.LogicalValue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamoLogicalValueConverterTest {

    private final DynamoLogicalValueConverter converter = new DynamoLogicalValueConverter();

    @Test
    void rejectsNullRecord() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> converter.toAttributeMap(null));
        assertEquals("Logical record must not be null.", exception.getMessage());
    }

    @Test
    void convertsString() {
        assertEquals(AttributeValue.builder().s("Product").build(), convert(new LogicalValue.StringValue("Product")));
    }

    @Test
    void convertsInteger() {
        assertEquals(AttributeValue.builder().n("5000000000").build(),
                convert(new LogicalValue.IntegerValue(5_000_000_000L)));
    }

    @Test
    void convertsIntegerBoundariesAndZero() {
        assertEquals("-9223372036854775808", convert(new LogicalValue.IntegerValue(Long.MIN_VALUE)).n());
        assertEquals("9223372036854775807", convert(new LogicalValue.IntegerValue(Long.MAX_VALUE)).n());
        assertEquals("0", convert(new LogicalValue.IntegerValue(0)).n());
    }

    @Test
    void convertsDecimalWithoutLosingPrecision() {
        BigDecimal number = new BigDecimal("12345678901234567890.123456789012345678");
        assertEquals(AttributeValue.builder().n("12345678901234567890.123456789012345678").build(),
                convert(new LogicalValue.DecimalValue(number)));
    }

    @Test
    void convertsDecimalExponentToPlainString() {
        assertEquals("12300000", convert(new LogicalValue.DecimalValue(new BigDecimal("1.23E+7"))).n());
        assertEquals("0.000000123", convert(new LogicalValue.DecimalValue(new BigDecimal("1.23E-7"))).n());
    }

    @Test
    void preservesDecimalScaleAndSign() {
        assertEquals("-12.500", convert(new LogicalValue.DecimalValue(new BigDecimal("-12.500"))).n());
        assertEquals("0.00", convert(new LogicalValue.DecimalValue(new BigDecimal("0.00"))).n());
    }

    @Test
    void convertsBooleanTrueAndFalse() {
        assertEquals(AttributeValue.builder().bool(true).build(), convert(new LogicalValue.BooleanValue(true)));
        assertEquals(AttributeValue.builder().bool(false).build(), convert(new LogicalValue.BooleanValue(false)));
    }

    @Test
    void convertsBinaryToNativeBytes() {
        byte[] input = {0, 1, -1, 127};
        LogicalValue.BinaryValue binary = new LogicalValue.BinaryValue(input);
        AttributeValue result = convert(binary);
        input[0] = 9;
        byte[] logicalBytes = binary.value();
        logicalBytes[1] = 9;
        assertArrayEquals(new byte[] {0, 1, -1, 127}, result.b().asByteArray());
        assertEquals(AttributeValue.builder().b(SdkBytes.fromByteArray(new byte[] {0, 1, -1, 127})).build(), result);
    }

    @Test
    void convertsNullToPresentNativeNull() {
        Map<String, AttributeValue> result = converter.toAttributeMap(
                new LogicalRecord(Map.of("description", new LogicalValue.NullValue())));
        assertTrue(result.containsKey("description"));
        assertEquals(Boolean.TRUE, result.get("description").nul());
        assertEquals(AttributeValue.builder().nul(true).build(), result.get("description"));
        assertFalse(result.containsKey("missing"));
    }

    @Test
    void convertsNestedObject() {
        LogicalValue.ObjectValue category = new LogicalValue.ObjectValue(Map.of(
                "id", new LogicalValue.StringValue("C01"),
                "name", new LogicalValue.StringValue("Informatica")));
        AttributeValue expected = AttributeValue.builder().m(Map.of("category",
                AttributeValue.builder().m(Map.of(
                        "id", AttributeValue.builder().s("C01").build(),
                        "name", AttributeValue.builder().s("Informatica").build())).build())).build();
        assertEquals(expected, convert(new LogicalValue.ObjectValue(Map.of("category", category))));
    }

    @Test
    void convertsArrayPreservingOrderTypesDuplicatesAndNull() {
        LogicalValue.ArrayValue array = new LogicalValue.ArrayValue(List.of(
                new LogicalValue.StringValue("a"), new LogicalValue.IntegerValue(2),
                new LogicalValue.BooleanValue(true), new LogicalValue.NullValue(), new LogicalValue.StringValue("a")));
        AttributeValue expected = AttributeValue.builder().l(List.of(
                AttributeValue.builder().s("a").build(), AttributeValue.builder().n("2").build(),
                AttributeValue.builder().bool(true).build(), AttributeValue.builder().nul(true).build(),
                AttributeValue.builder().s("a").build())).build();
        assertEquals(expected, convert(array));
    }

    @Test
    void convertsRecursiveObjectAndArrayCombination() {
        LogicalValue.ObjectValue item = new LogicalValue.ObjectValue(Map.of(
                "name", new LogicalValue.StringValue("A"),
                "tags", new LogicalValue.ArrayValue(List.of(new LogicalValue.StringValue("portable")))));
        LogicalValue.ObjectValue root = new LogicalValue.ObjectValue(Map.of(
                "items", new LogicalValue.ArrayValue(List.of(item, new LogicalValue.NullValue()))));
        AttributeValue expectedItem = AttributeValue.builder().m(Map.of(
                "name", AttributeValue.builder().s("A").build(),
                "tags", AttributeValue.builder().l(List.of(AttributeValue.builder().s("portable").build())).build()))
                .build();
        AttributeValue expected = AttributeValue.builder().m(Map.of("items", AttributeValue.builder()
                .l(List.of(expectedItem, AttributeValue.builder().nul(true).build())).build())).build();
        assertEquals(expected, convert(root));
    }

    @Test
    void convertsNestedLists() {
        LogicalValue.ArrayValue array = new LogicalValue.ArrayValue(List.of(new LogicalValue.ArrayValue(
                List.of(new LogicalValue.IntegerValue(2), new LogicalValue.NullValue()))));
        assertEquals(AttributeValue.builder().l(List.of(AttributeValue.builder().l(List.of(
                AttributeValue.builder().n("2").build(), AttributeValue.builder().nul(true).build())).build())).build(),
                convert(array));
    }

    @Test
    void rejectsTimestampWithoutCoercion() {
        LogicalValue.TimestampValue timestamp = new LogicalValue.TimestampValue(Instant.parse("2026-10-10T10:00:00Z"));
        assertTimestampRejected(timestamp);
    }

    @Test
    void rejectsTimestampNestedInObject() {
        assertTimestampRejected(new LogicalValue.ObjectValue(Map.of("created",
                new LogicalValue.TimestampValue(Instant.parse("2026-10-10T10:00:00Z")))));
    }

    @Test
    void rejectsTimestampNestedInArray() {
        assertTimestampRejected(new LogicalValue.ArrayValue(List.of(
                new LogicalValue.TimestampValue(Instant.parse("2026-10-10T10:00:00Z")))));
    }

    @Test
    void convertsCompleteRecord() {
        LogicalRecord record = new LogicalRecord(Map.of(
                "id", new LogicalValue.StringValue("P001"),
                "name", new LogicalValue.StringValue("Portatil Lenovo"),
                "price", new LogicalValue.DecimalValue(new BigDecimal("899.99")),
                "stock", new LogicalValue.IntegerValue(20),
                "active", new LogicalValue.BooleanValue(true),
                "metadata", new LogicalValue.ObjectValue(Map.of("category", new LogicalValue.StringValue("C01")))));
        Map<String, AttributeValue> expected = Map.of(
                "id", AttributeValue.builder().s("P001").build(),
                "name", AttributeValue.builder().s("Portatil Lenovo").build(),
                "price", AttributeValue.builder().n("899.99").build(),
                "stock", AttributeValue.builder().n("20").build(),
                "active", AttributeValue.builder().bool(true).build(),
                "metadata", AttributeValue.builder().m(Map.of("category", AttributeValue.builder().s("C01").build()))
                        .build());
        assertEquals(expected, converter.toAttributeMap(record));
    }

    @Test
    void convertsEmptyRecord() {
        assertEquals(Map.of(), converter.toAttributeMap(new LogicalRecord(Map.of())));
    }

    @Test
    void convertsEmptyObjectsAndArraysAsNativeContainers() {
        AttributeValue object = convert(new LogicalValue.ObjectValue(Map.of()));
        AttributeValue array = convert(new LogicalValue.ArrayValue(List.of()));
        assertTrue(object.hasM());
        assertEquals(Map.of(), object.m());
        assertTrue(array.hasL());
        assertEquals(List.of(), array.l());
    }

    @Test
    void preservesLiteralFieldNames() {
        LogicalRecord record = new LogicalRecord(Map.of("a.b", new LogicalValue.ObjectValue(
                Map.of("c.d", new LogicalValue.StringValue("value")))));
        assertEquals(Map.of("a.b", AttributeValue.builder().m(Map.of(
                "c.d", AttributeValue.builder().s("value").build())).build()), converter.toAttributeMap(record));
    }

    private AttributeValue convert(LogicalValue value) {
        return converter.toAttributeMap(new LogicalRecord(Map.of("value", value))).get("value");
    }

    private void assertTimestampRejected(LogicalValue value) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> convert(value));
        assertEquals("TimestampValue is not supported directly by DynamoDB; transform it before writing.",
                exception.getMessage());
    }
}
