package dev.borja.nosqlbridge.common.model;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogicalRecordTest {

    private Map<String, LogicalValue> fields;
    private LogicalRecord record;

    @BeforeEach
    void setUp() {
        fields = new HashMap<>();
        fields.put("name", new LogicalValue.StringValue("Product"));
        fields.put("stock", new LogicalValue.IntegerValue(10));
        fields.put("active", new LogicalValue.BooleanValue(true));
        fields.put("description", new LogicalValue.NullValue());
        record = new LogicalRecord(fields);
    }

    @Test
    void retrievesFields() {
        assertEquals(new LogicalValue.StringValue("Product"), record.get("name"));
        assertEquals(new LogicalValue.IntegerValue(10), record.get("stock"));
        assertEquals(new LogicalValue.BooleanValue(true), record.get("active"));
        assertEquals(new LogicalValue.NullValue(), record.get("description"));
        assertEquals(4, record.fields().size());
    }

    @Test
    void distinguishesMissingFieldsFromLogicalNull() {
        assertTrue(record.contains("name"));
        assertTrue(record.contains("description"));
        assertFalse(record.contains("missing"));
        assertNull(record.get("missing"));
    }

    @Test
    void copiesOriginalMap() {
        fields.put("name", new LogicalValue.StringValue("Changed"));
        fields.remove("stock");
        fields.put("extra", new LogicalValue.BooleanValue(false));

        assertEquals(new LogicalValue.StringValue("Product"), record.get("name"));
        assertTrue(record.contains("stock"));
        assertFalse(record.contains("extra"));
        assertEquals(4, record.fields().size());
    }

    @Test
    void exposesUnmodifiableFields() {
        assertThrows(UnsupportedOperationException.class,
                () -> record.fields().put("extra", new LogicalValue.IntegerValue(1)));
        assertThrows(UnsupportedOperationException.class, () -> record.fields().clear());
        assertEquals(4, record.fields().size());
    }
}
