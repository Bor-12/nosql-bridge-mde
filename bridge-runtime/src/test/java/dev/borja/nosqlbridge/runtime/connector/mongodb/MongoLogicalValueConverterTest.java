package dev.borja.nosqlbridge.runtime.connector.mongodb;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.bson.BsonTimestamp;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import dev.borja.nosqlbridge.runtime.model.LogicalRecord;
import dev.borja.nosqlbridge.runtime.model.LogicalValue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MongoLogicalValueConverterTest {

    private final MongoLogicalValueConverter converter = new MongoLogicalValueConverter();

    @Test
    void convertsString() {
        assertEquals(new LogicalValue.StringValue("Product"), convert("Product"));
    }

    @Test
    void convertsInteger() {
        assertEquals(new LogicalValue.IntegerValue(10), convert(Integer.valueOf(10)));
    }

    @Test
    void convertsLong() {
        assertEquals(new LogicalValue.IntegerValue(5_000_000_000L), convert(Long.valueOf(5_000_000_000L)));
    }

    @Test
    void convertsShort() {
        assertEquals(new LogicalValue.IntegerValue(12), convert(Short.valueOf((short) 12)));
    }

    @Test
    void convertsByte() {
        assertEquals(new LogicalValue.IntegerValue(7), convert(Byte.valueOf((byte) 7)));
    }

    @Test
    void convertsDecimalDouble() {
        assertEquals(new LogicalValue.DecimalValue(new BigDecimal("899.99")), convert(Double.valueOf(899.99)));
    }

    @Test
    void convertsDecimalFloat() {
        Float number = Float.valueOf(0.1f);
        assertEquals(new LogicalValue.DecimalValue(BigDecimal.valueOf(number.doubleValue())), convert(number));
    }

    @Test
    void convertsBigDecimal() {
        assertEquals(new LogicalValue.DecimalValue(new BigDecimal("12.50")), convert(new BigDecimal("12.50")));
    }

    @Test
    void convertsDecimal128() {
        assertEquals(new LogicalValue.DecimalValue(new BigDecimal("12.50")),
                convert(new Decimal128(new BigDecimal("12.50"))));
    }

    @Test
    void convertsBoolean() {
        assertEquals(new LogicalValue.BooleanValue(true), convert(Boolean.TRUE));
        assertEquals(new LogicalValue.BooleanValue(false), convert(Boolean.FALSE));
    }

    @Test
    void convertsByteArray() {
        byte[] bytes = {1, 2, 3};
        LogicalValue.BinaryValue result = assertInstanceOf(LogicalValue.BinaryValue.class, convert(bytes));
        bytes[0] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, result.value());
        byte[] output = result.value();
        output[1] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, result.value());
    }

    @Test
    void convertsBsonBinary() {
        Binary binary = new Binary(new byte[] {4, 5, 6});
        LogicalValue.BinaryValue result = assertInstanceOf(LogicalValue.BinaryValue.class, convert(binary));
        binary.getData()[0] = 9;
        assertArrayEquals(new byte[] {4, 5, 6}, result.value());
    }

    @Test
    void convertsDateToTimestamp() {
        Instant instant = Instant.parse("2026-10-10T10:00:00.123Z");
        Date date = Date.from(instant);
        LogicalValue result = convert(date);
        date.setTime(0);
        assertEquals(new LogicalValue.TimestampValue(instant), result);
    }

    @Test
    void convertsDriverDecodedDateTime() {
        Document document = Document.parse("{\"created\": {\"$date\": \"2026-10-10T10:00:00.123Z\"}}");
        assertInstanceOf(Date.class, document.get("created"));
        assertEquals(new LogicalValue.TimestampValue(Instant.parse("2026-10-10T10:00:00.123Z")),
                converter.toLogicalRecord(document).get("created"));
    }

    @Test
    void convertsNullToLogicalNull() {
        assertEquals(new LogicalValue.NullValue(), convert(null));
    }

    @Test
    void convertsObjectIdToString() {
        ObjectId id = new ObjectId("507f1f77bcf86cd799439011");
        LogicalRecord result = converter.toLogicalRecord(new Document("_id", id));
        assertEquals(new LogicalValue.StringValue("507f1f77bcf86cd799439011"), result.get("_id"));
    }

    @Test
    void convertsNestedDocumentRecursively() {
        Document nested = new Document("category", new Document("id", "C01")
                .append("name", "Informatica").append("description", null));
        LogicalValue expected = new LogicalValue.ObjectValue(Map.of("category",
                new LogicalValue.ObjectValue(Map.of(
                        "id", new LogicalValue.StringValue("C01"),
                        "name", new LogicalValue.StringValue("Informatica"),
                        "description", new LogicalValue.NullValue()))));
        assertEquals(expected, convert(nested));
    }

    @Test
    void convertsListRecursively() {
        List<Object> values = Arrays.asList("computer", Integer.valueOf(10), Boolean.TRUE, null);
        assertEquals(new LogicalValue.ArrayValue(List.of(
                new LogicalValue.StringValue("computer"), new LogicalValue.IntegerValue(10),
                new LogicalValue.BooleanValue(true), new LogicalValue.NullValue())), convert(values));
    }

    @Test
    void convertsNestedDocumentsInsideList() {
        List<Document> items = List.of(new Document("name", "A").append("price", 10.5),
                new Document("name", "B").append("price", 20.0));
        assertEquals(new LogicalValue.ArrayValue(List.of(
                new LogicalValue.ObjectValue(Map.of("name", new LogicalValue.StringValue("A"),
                        "price", new LogicalValue.DecimalValue(new BigDecimal("10.5")))),
                new LogicalValue.ObjectValue(Map.of("name", new LogicalValue.StringValue("B"),
                        "price", new LogicalValue.DecimalValue(new BigDecimal("20.0")))))), convert(items));
    }

    @Test
    void convertsNestedLists() {
        List<Object> values = List.of(List.of("A", List.of(new Document("name", "B"))));
        LogicalValue expected = new LogicalValue.ArrayValue(List.of(new LogicalValue.ArrayValue(List.of(
                new LogicalValue.StringValue("A"), new LogicalValue.ArrayValue(List.of(
                        new LogicalValue.ObjectValue(Map.of("name", new LogicalValue.StringValue("B")))))))));
        assertEquals(expected, convert(values));
    }

    @Test
    void convertsCompleteDocumentToLogicalRecord() {
        Document document = new Document("_id", "P001").append("name", "Portatil Lenovo")
                .append("price", 899.99).append("stock", 20).append("active", true)
                .append("description", null)
                .append("category", new Document("id", "C01").append("name", "Informatica"))
                .append("tags", List.of("laptop", "computer"));
        Map<String, LogicalValue> expected = Map.of(
                "_id", new LogicalValue.StringValue("P001"),
                "name", new LogicalValue.StringValue("Portatil Lenovo"),
                "price", new LogicalValue.DecimalValue(new BigDecimal("899.99")),
                "stock", new LogicalValue.IntegerValue(20),
                "active", new LogicalValue.BooleanValue(true),
                "description", new LogicalValue.NullValue(),
                "category", new LogicalValue.ObjectValue(Map.of(
                        "id", new LogicalValue.StringValue("C01"),
                        "name", new LogicalValue.StringValue("Informatica"))),
                "tags", new LogicalValue.ArrayValue(List.of(
                        new LogicalValue.StringValue("laptop"), new LogicalValue.StringValue("computer"))));
        assertEquals(new LogicalRecord(expected), converter.toLogicalRecord(document));
    }

    @Test
    void distinguishesNullFieldFromMissingField() {
        Document document = new Document();
        document.put("description", null);
        LogicalRecord result = converter.toLogicalRecord(document);
        assertTrue(result.contains("description"));
        assertInstanceOf(LogicalValue.NullValue.class, result.get("description"));
        assertFalse(result.contains("missing"));
    }

    @Test
    void preservesLiteralDottedFieldNames() {
        Document document = new Document("a.b", "top").append("nested", new Document("c.d", "inner"));
        LogicalRecord result = converter.toLogicalRecord(document);
        assertEquals(new LogicalValue.StringValue("top"), result.get("a.b"));
        assertFalse(result.contains("a"));
        assertEquals(new LogicalValue.ObjectValue(Map.of("c.d", new LogicalValue.StringValue("inner"))),
                result.get("nested"));
    }

    @Test
    void convertsEmptyDocumentAndContainers() {
        assertEquals(new LogicalRecord(Map.of()), converter.toLogicalRecord(new Document()));
        assertEquals(new LogicalValue.ObjectValue(Map.of()), convert(new Document()));
        assertEquals(new LogicalValue.ArrayValue(List.of()), convert(List.of()));
    }

    @Test
    void copiesMutableDocumentAndListInputs() {
        Document nested = new Document("name", "A");
        List<Object> items = new ArrayList<>(List.of(nested));
        Document document = new Document("items", items);
        LogicalRecord result = converter.toLogicalRecord(document);
        nested.put("name", "Changed");
        items.clear();
        document.clear();
        assertEquals(new LogicalRecord(Map.of("items", new LogicalValue.ArrayValue(List.of(
                new LogicalValue.ObjectValue(Map.of("name", new LogicalValue.StringValue("A"))))))), result);
    }

    @Test
    void rejectsUnsupportedType() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> convert(new Object()));
        assertEquals("Unsupported MongoDB value type: java.lang.Object", exception.getMessage());
    }

    @Test
    void rejectsUnsupportedNestedType() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> convert(List.of(new Document("unsupported", new Object()))));
        assertEquals("Unsupported MongoDB value type: java.lang.Object", exception.getMessage());
    }

    @Test
    void rejectsBsonTimestamp() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> convert(new BsonTimestamp(1, 2)));
        assertEquals("Unsupported MongoDB value type: org.bson.BsonTimestamp", exception.getMessage());
    }

    @Test
    void rejectsNullDocument() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> converter.toLogicalRecord(null));
        assertEquals("MongoDB document must not be null.", exception.getMessage());
    }

    @Test
    void rejectsNaNDouble() {
        assertNonFiniteRejected(Double.NaN, "NaN");
    }

    @Test
    void rejectsPositiveInfinityDouble() {
        assertNonFiniteRejected(Double.POSITIVE_INFINITY, "Infinity");
    }

    @Test
    void rejectsNegativeInfinityDouble() {
        assertNonFiniteRejected(Double.NEGATIVE_INFINITY, "-Infinity");
    }

    @Test
    void rejectsNonFiniteFloats() {
        assertNonFiniteRejected(Float.NaN, "NaN");
        assertNonFiniteRejected(Float.POSITIVE_INFINITY, "Infinity");
        assertNonFiniteRejected(Float.NEGATIVE_INFINITY, "-Infinity");
    }

    @Test
    void rejectsNonFiniteDecimal128() {
        assertNonFiniteRejected(Decimal128.NaN, "NaN");
        assertNonFiniteRejected(Decimal128.POSITIVE_INFINITY, "Infinity");
        assertNonFiniteRejected(Decimal128.NEGATIVE_INFINITY, "-Infinity");
    }

    private LogicalValue convert(Object value) {
        return converter.toLogicalRecord(new Document("value", value)).get("value");
    }

    private void assertNonFiniteRejected(Object value, String representation) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> convert(value));
        assertEquals("Non-finite MongoDB numeric value: " + representation, exception.getMessage());
    }
}
