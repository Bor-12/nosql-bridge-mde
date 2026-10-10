package dev.borja.nosqlbridge.runtime.model;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class LogicalPathResolverTest {

    private LogicalPathResolver resolver;
    private LogicalRecord record;

    @BeforeEach
    void setUp() {
        resolver = new LogicalPathResolver();
        LogicalValue.ObjectValue category = new LogicalValue.ObjectValue(Map.of(
                "id", new LogicalValue.StringValue("C01"),
                "name", new LogicalValue.StringValue("Informatica")));
        LogicalValue.ObjectValue manufacturer = new LogicalValue.ObjectValue(Map.of(
                "name", new LogicalValue.StringValue("Lenovo")));
        LogicalValue.ObjectValue metadata = new LogicalValue.ObjectValue(Map.of(
                "manufacturer", manufacturer));
        LogicalValue.ArrayValue items = new LogicalValue.ArrayValue(List.of(category));
        record = new LogicalRecord(Map.of(
                "name", new LogicalValue.StringValue("Product"),
                "category", category,
                "metadata", metadata,
                "description", new LogicalValue.NullValue(),
                "items", items));
    }

    @Test
    void resolvesRootField() {
        assertEquals(new LogicalValue.StringValue("Product"), resolver.resolve(record, "name"));
    }

    @Test
    void resolvesNestedField() {
        assertEquals(new LogicalValue.StringValue("Informatica"),
                resolver.resolve(record, "category.name"));
    }

    @Test
    void resolvesMultipleNestedLevels() {
        assertEquals(new LogicalValue.StringValue("Lenovo"),
                resolver.resolve(record, "metadata.manufacturer.name"));
    }

    @Test
    void returnsNullForMissingRootField() {
        assertNull(resolver.resolve(record, "missing"));
    }

    @Test
    void returnsNullForMissingNestedSegment() {
        assertNull(resolver.resolve(record, "category.missing"));
        assertNull(resolver.resolve(record, "metadata.missing.name"));
    }

    @Test
    void returnsNullWhenContinuingThroughScalar() {
        assertNull(resolver.resolve(record, "name.first"));
    }

    @Test
    void returnsLogicalNullValue() {
        assertSame(record.get("description"), resolver.resolve(record, "description"));
    }

    @Test
    void returnsNullWhenContinuingThroughLogicalNull() {
        assertNull(resolver.resolve(record, "description.name"));
    }

    @Test
    void doesNotResolveArrayIndices() {
        assertNull(resolver.resolve(record, "items.0.name"));
    }
}
