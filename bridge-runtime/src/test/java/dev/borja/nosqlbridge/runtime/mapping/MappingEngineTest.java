package dev.borja.nosqlbridge.runtime.mapping;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.borja.nosqlbridge.runtime.model.LogicalPathResolver;
import dev.borja.nosqlbridge.runtime.model.LogicalRecord;
import dev.borja.nosqlbridge.runtime.model.LogicalValue;
import dev.borja.nosqlbridge.runtime.plan.MappingDefaultValue;
import dev.borja.nosqlbridge.runtime.plan.MappingPlan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingEngineTest {

    private LogicalPathResolver pathResolver;
    private MappingEngine engine;
    private LogicalRecord source;

    @BeforeEach
    void setUp() {
        pathResolver = new LogicalPathResolver();
        engine = new MappingEngine(pathResolver);
        LogicalValue.ObjectValue category = new LogicalValue.ObjectValue(Map.of(
                "name", new LogicalValue.StringValue("Informatica")));
        source = new LogicalRecord(Map.of(
                "name", new LogicalValue.StringValue("Product"),
                "stock", new LogicalValue.IntegerValue(10),
                "category", category,
                "description", new LogicalValue.NullValue()));
    }

    @Test
    void mapsRootField() {
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("name", "title", null, null)));

        assertEquals(new LogicalValue.StringValue("Product"), result.get("title"));
    }

    @Test
    void mapsNestedField() {
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("category.name", "categoryName", null, null)));

        assertEquals(new LogicalValue.StringValue("Informatica"), result.get("categoryName"));
    }

    @Test
    void mapsMultipleFields() {
        List<MappingPlan> mappings = List.of(
                new MappingPlan("name", "title", null, null),
                new MappingPlan("stock", "quantity", null, null),
                new MappingPlan("category.name", "categoryName", null, null));
        LogicalRecord result = engine.map(source, mappings);

        assertEquals(Map.of(
                "title", new LogicalValue.StringValue("Product"),
                "quantity", new LogicalValue.IntegerValue(10),
                "categoryName", new LogicalValue.StringValue("Informatica")), result.fields());
    }

    @Test
    void omitsMissingSourceField() {
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("missing", "absent", null, null)));

        assertFalse(result.contains("absent"));
        assertTrue(result.fields().isEmpty());
    }

    @Test
    void copiesLogicalNullValue() {
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("description", "details", null, null)));

        assertTrue(result.contains("details"));
        assertSame(source.get("description"), result.get("details"));
    }

    @Test
    void includesOnlyExplicitlyMappedFields() {
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("name", "title", null, null)));

        assertEquals(Map.of("title", new LogicalValue.StringValue("Product")), result.fields());
    }

    @Test
    void ignoresTransformationsAndDefaults() {
        MappingDefaultValue defaultValue = new MappingDefaultValue("string", "Fallback");
        List<MappingPlan> mappings = List.of(
                new MappingPlan("stock", "quantity", "toString", defaultValue),
                new MappingPlan("missing", "absent", null, defaultValue));
        LogicalRecord result = engine.map(source, mappings);

        assertEquals(Map.of("quantity", new LogicalValue.IntegerValue(10)), result.fields());
    }

    @Test
    void treatsTargetPathAsLiteralFieldName() {
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("name", "product.name", null, null)));

        assertEquals(new LogicalValue.StringValue("Product"), result.get("product.name"));
        assertFalse(result.contains("product"));
    }
}
