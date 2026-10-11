package dev.borja.nosqlbridge.runtime.mapping;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.borja.nosqlbridge.runtime.model.LogicalPathResolver;
import dev.borja.nosqlbridge.runtime.model.LogicalRecord;
import dev.borja.nosqlbridge.runtime.model.LogicalValue;
import dev.borja.nosqlbridge.runtime.plan.MappingDefaultValue;
import dev.borja.nosqlbridge.runtime.plan.MappingPlan;
import dev.borja.nosqlbridge.runtime.transformation.TransformationEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingEngineTest {

    private LogicalPathResolver pathResolver;
    private TransformationEngine transformationEngine;
    private DefaultValueConverter defaultValueConverter;
    private MappingEngine engine;
    private LogicalRecord source;

    @BeforeEach
    void setUp() {
        pathResolver = new LogicalPathResolver();
        transformationEngine = new TransformationEngine();
        defaultValueConverter = new DefaultValueConverter();
        engine = new MappingEngine(pathResolver, transformationEngine, defaultValueConverter);
        LogicalValue.ObjectValue category = new LogicalValue.ObjectValue(Map.of(
                "name", new LogicalValue.StringValue("Informatica")));
        source = new LogicalRecord(Map.of(
                "name", new LogicalValue.StringValue("Product"),
                "stock", new LogicalValue.IntegerValue(10),
                "numericText", new LogicalValue.StringValue("25"),
                "category", category,
                "description", new LogicalValue.NullValue()));
    }

    @Test
    void mapsRootField() {
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("name", "title", null, null)));

        assertEquals(new LogicalValue.StringValue("Product"), result.get("title"));
        assertSame(source.get("name"), result.get("title"));
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
                List.of(new MappingPlan("missing", "absent", "unknown", null)));

        assertFalse(result.contains("absent"));
        assertTrue(result.fields().isEmpty());
    }

    @Test
    void copiesLogicalNullValue() {
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("description", "details", "toInteger", null)));

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
    void appliesStringDefaultWhenSourceFieldIsMissing() {
        MappingDefaultValue defaultValue = new MappingDefaultValue("string", "Fallback");
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("missing", "title", null, defaultValue)));

        assertEquals(new LogicalValue.StringValue("Fallback"), result.get("title"));
    }

    @Test
    void appliesDefaultWhenSourceFieldIsLogicalNull() {
        MappingDefaultValue defaultValue = new MappingDefaultValue("string", "Fallback");
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("description", "details", null, defaultValue)));

        assertEquals(new LogicalValue.StringValue("Fallback"), result.get("details"));
    }

    @Test
    void ignoresDefaultWhenSourceFieldHasValue() {
        MappingDefaultValue defaultValue = new MappingDefaultValue("string", "Fallback");
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("name", "title", null, defaultValue)));

        assertEquals(new LogicalValue.StringValue("Product"), result.get("title"));
    }

    @Test
    void appliesDefaultBeforeTransformation() {
        MappingDefaultValue defaultValue = new MappingDefaultValue("string", "25");
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("missing", "quantity", "toInteger", defaultValue)));

        assertEquals(new LogicalValue.IntegerValue(25), result.get("quantity"));
    }



    @Test
    void treatsTargetPathAsLiteralFieldName() {
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("name", "product.name", null, null)));

        assertEquals(new LogicalValue.StringValue("Product"), result.get("product.name"));
        assertFalse(result.contains("product"));
    }

    @Test
    void transformsRootFieldToString() {
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("stock", "quantity", "toString", null)));

        assertEquals(new LogicalValue.StringValue("10"), result.get("quantity"));
    }

    @Test
    void resolvesAndTransformsNestedField() {
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("category.name", "categoryName", "toString", null)));

        assertEquals(new LogicalValue.StringValue("Informatica"), result.get("categoryName"));
    }

    @Test
    void transformsNumericStringToInteger() {
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("numericText", "quantity", "toInteger", null)));

        assertEquals(new LogicalValue.IntegerValue(25), result.get("quantity"));
    }

    @Test
    void transformsIntegerToDecimal() {
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("stock", "quantity", "toDecimal", null)));

        assertEquals(new LogicalValue.DecimalValue(BigDecimal.valueOf(10)), result.get("quantity"));
    }

    @Test
    void propagatesInvalidTransformationException() {
        List<MappingPlan> mappings = List.of(new MappingPlan("stock", "quantity", "unknown", null));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> engine.map(source, mappings));
        assertEquals("Unknown transformation: unknown", exception.getMessage());
    }

    @Test
    void propagatesInvalidTransformationExceptionForDefaultValue() {
        MappingDefaultValue defaultValue = new MappingDefaultValue("string", "Fallback");
        List<MappingPlan> mappings = List.of(new MappingPlan("missing", "title", "unknown", defaultValue));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> engine.map(source, mappings));
        assertEquals("Unknown transformation: unknown", exception.getMessage());
    }
}
