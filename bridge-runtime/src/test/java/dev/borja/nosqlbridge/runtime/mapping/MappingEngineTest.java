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
                List.of(new MappingPlan("name", "title", null, null)), "preserve", "drop");

        assertEquals(new LogicalValue.StringValue("Product"), result.get("title"));
        assertSame(source.get("name"), result.get("title"));
    }

    @Test
    void mapsNestedField() {
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("category.name", "categoryName", null, null)), "preserve", "drop");

        assertEquals(new LogicalValue.StringValue("Informatica"), result.get("categoryName"));
    }

    @Test
    void mapsMultipleFields() {
        List<MappingPlan> mappings = List.of(
                new MappingPlan("name", "title", null, null),
                new MappingPlan("stock", "quantity", null, null),
                new MappingPlan("category.name", "categoryName", null, null));
        LogicalRecord result = engine.map(source, mappings, "preserve", "drop");

        assertEquals(Map.of(
                "title", new LogicalValue.StringValue("Product"),
                "quantity", new LogicalValue.IntegerValue(10),
                "categoryName", new LogicalValue.StringValue("Informatica")), result.fields());
    }

    @Test
    void missingFieldIsOmittedWithPreservePolicy() {
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("missing", "absent", "unknown", null)), "preserve", "drop");

        assertFalse(result.contains("absent"));
        assertTrue(result.fields().isEmpty());
    }

    @Test
    void preservesLogicalNullWhenPolicyIsPreserve() {
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("description", "details", "toInteger", null)), "preserve", "drop");

        assertTrue(result.contains("details"));
        assertEquals(new LogicalValue.NullValue(), result.get("details"));
        assertSame(source.get("description"), result.get("details"));
    }

    @Test
    void includesOnlyExplicitlyMappedFields() {
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("name", "title", null, null)), "preserve", "drop");

        assertEquals(Map.of("title", new LogicalValue.StringValue("Product")), result.fields());
    }

    @Test
    void dropsUnmappedFieldsWhenPolicyIsDrop() {
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("name", "title", null, null)), "preserve", "drop");

        assertEquals(Map.of("title", new LogicalValue.StringValue("Product")), result.fields());
    }

    @Test
    void preservesUnmappedFieldsWhenPolicyIsPreserve() {
        LogicalRecord simpleSource = new LogicalRecord(Map.of(
                "name", new LogicalValue.StringValue("Product"),
                "stock", new LogicalValue.IntegerValue(10)));

        LogicalRecord result = engine.map(simpleSource,
                List.of(new MappingPlan("name", "title", null, null)), "preserve", "preserve");

        assertEquals(new LogicalValue.StringValue("Product"), result.get("title"));
        assertEquals(new LogicalValue.IntegerValue(10), result.get("stock"));
        assertFalse(result.contains("name"));
    }

    @Test
    void doesNotPreserveRenamedSourceField() {
        LogicalRecord simpleSource = new LogicalRecord(Map.of(
                "name", new LogicalValue.StringValue("Product"),
                "stock", new LogicalValue.IntegerValue(10)));

        LogicalRecord result = engine.map(simpleSource,
                List.of(new MappingPlan("name", "title", null, null)), "preserve", "preserve");

        assertEquals(new LogicalValue.StringValue("Product"), result.get("title"));
        assertEquals(new LogicalValue.IntegerValue(10), result.get("stock"));
        assertFalse(result.contains("name"));
    }

    @Test
    void preservesUnmappedLogicalNullRegardlessOfNullPolicy() {
        LogicalRecord simpleSource = new LogicalRecord(Map.of(
                "name", new LogicalValue.StringValue("Product"),
                "description", new LogicalValue.NullValue()));

        LogicalRecord result = engine.map(simpleSource,
                List.of(new MappingPlan("name", "title", null, null)), "omit", "preserve");

        assertEquals(new LogicalValue.StringValue("Product"), result.get("title"));
        assertTrue(result.contains("description"));
        assertTrue(result.get("description") instanceof LogicalValue.NullValue);
    }

    @Test
    void nestedMappingConsumesTopLevelSourceField() {
        LogicalValue.ObjectValue category = new LogicalValue.ObjectValue(Map.of(
                "name", new LogicalValue.StringValue("Informatica"),
                "code", new LogicalValue.StringValue("C01")));
        LogicalRecord nestedSource = new LogicalRecord(Map.of(
                "category", category,
                "stock", new LogicalValue.IntegerValue(10)));

        LogicalRecord result = engine.map(nestedSource,
                List.of(new MappingPlan("category.name", "categoryName", null, null)), "preserve", "preserve");

        assertEquals(new LogicalValue.StringValue("Informatica"), result.get("categoryName"));
        assertEquals(new LogicalValue.IntegerValue(10), result.get("stock"));
        assertFalse(result.contains("category"));
    }

    @Test
    void deeplyNestedMappingConsumesTopLevelSourceField() {
        LogicalValue.ObjectValue address = new LogicalValue.ObjectValue(Map.of(
                "city", new LogicalValue.StringValue("Madrid")));
        LogicalValue.ObjectValue user = new LogicalValue.ObjectValue(Map.of(
                "address", address));
        LogicalRecord nestedSource = new LogicalRecord(Map.of(
                "user", user,
                "stock", new LogicalValue.IntegerValue(10)));

        LogicalRecord result = engine.map(nestedSource,
                List.of(new MappingPlan("user.address.city", "city", null, null)), "preserve", "preserve");

        assertEquals(new LogicalValue.StringValue("Madrid"), result.get("city"));
        assertEquals(new LogicalValue.IntegerValue(10), result.get("stock"));
        assertFalse(result.contains("user"));
    }

    @Test
    void mappedTargetOverridesPreservedFieldWithSameName() {
        LogicalRecord collisionSource = new LogicalRecord(Map.of(
                "name", new LogicalValue.StringValue("Product"),
                "title", new LogicalValue.StringValue("Old title")));

        LogicalRecord result = engine.map(collisionSource,
                List.of(new MappingPlan("name", "title", null, null)), "preserve", "preserve");

        assertEquals(Map.of("title", new LogicalValue.StringValue("Product")), result.fields());
    }

    @Test
    void defaultStillWorksWithPreserve() {
        MappingDefaultValue defaultValue = new MappingDefaultValue("string", "Fallback");
        LogicalRecord simpleSource = new LogicalRecord(Map.of(
                "id", new LogicalValue.StringValue("P001"),
                "stock", new LogicalValue.IntegerValue(10)));

        LogicalRecord result = engine.map(simpleSource,
                List.of(new MappingPlan("missing", "title", null, defaultValue)), "preserve", "preserve");

        assertEquals(new LogicalValue.StringValue("P001"), result.get("id"));
        assertEquals(new LogicalValue.IntegerValue(10), result.get("stock"));
        assertEquals(new LogicalValue.StringValue("Fallback"), result.get("title"));
    }

    @Test
    void nullPolicyStillWorksForMappedFieldsWithPreserve() {
        LogicalRecord simpleSource = new LogicalRecord(Map.of(
                "description", new LogicalValue.NullValue(),
                "stock", new LogicalValue.IntegerValue(10)));

        LogicalRecord result = engine.map(simpleSource,
                List.of(new MappingPlan("description", "details", null, null)), "omit", "preserve");

        assertFalse(result.contains("details"));
        assertFalse(result.contains("description"));
        assertEquals(new LogicalValue.IntegerValue(10), result.get("stock"));
    }

    @Test
    void transformationStillWorksWithPreserve() {
        LogicalRecord simpleSource = new LogicalRecord(Map.of(
                "numericText", new LogicalValue.StringValue("25"),
                "name", new LogicalValue.StringValue("Product")));

        LogicalRecord result = engine.map(simpleSource,
                List.of(new MappingPlan("numericText", "quantity", "toInteger", null)), "preserve", "preserve");

        assertEquals(new LogicalValue.IntegerValue(25), result.get("quantity"));
        assertEquals(new LogicalValue.StringValue("Product"), result.get("name"));
        assertFalse(result.contains("numericText"));
    }

    @Test
    void appliesStringDefaultWhenSourceFieldIsMissing() {
        MappingDefaultValue defaultValue = new MappingDefaultValue("string", "Fallback");
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("missing", "title", null, defaultValue)), "preserve", "drop");

        assertEquals(new LogicalValue.StringValue("Fallback"), result.get("title"));
    }

    @Test
    void appliesDefaultWhenSourceFieldIsLogicalNull() {
        MappingDefaultValue defaultValue = new MappingDefaultValue("string", "Fallback");
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("description", "details", null, defaultValue)), "preserve", "drop");

        assertEquals(new LogicalValue.StringValue("Fallback"), result.get("details"));
    }

    @Test
    void ignoresDefaultWhenSourceFieldHasValue() {
        MappingDefaultValue defaultValue = new MappingDefaultValue("string", "Fallback");
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("name", "title", null, defaultValue)), "preserve", "drop");

        assertEquals(new LogicalValue.StringValue("Product"), result.get("title"));
    }

    @Test
    void defaultIsTransformedBeforeResultIsWritten() {
        MappingDefaultValue defaultValue = new MappingDefaultValue("string", "25");
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("missing", "quantity", "toInteger", defaultValue)), "omit", "drop");

        assertEquals(new LogicalValue.IntegerValue(25), result.get("quantity"));
    }

    @Test
    void treatsTargetPathAsLiteralFieldName() {
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("name", "product.name", null, null)), "preserve", "drop");

        assertEquals(new LogicalValue.StringValue("Product"), result.get("product.name"));
        assertFalse(result.contains("product"));
    }

    @Test
    void transformsRootFieldToString() {
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("stock", "quantity", "toString", null)), "preserve", "drop");

        assertEquals(new LogicalValue.StringValue("10"), result.get("quantity"));
    }

    @Test
    void resolvesAndTransformsNestedField() {
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("category.name", "categoryName", "toString", null)), "preserve", "drop");

        assertEquals(new LogicalValue.StringValue("Informatica"), result.get("categoryName"));
    }

    @Test
    void transformsNumericStringToInteger() {
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("numericText", "quantity", "toInteger", null)), "preserve", "drop");

        assertEquals(new LogicalValue.IntegerValue(25), result.get("quantity"));
    }

    @Test
    void transformsIntegerToDecimal() {
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("stock", "quantity", "toDecimal", null)), "preserve", "drop");

        assertEquals(new LogicalValue.DecimalValue(BigDecimal.valueOf(10)), result.get("quantity"));
    }

    @Test
    void propagatesInvalidTransformationException() {
        List<MappingPlan> mappings = List.of(new MappingPlan("stock", "quantity", "unknown", null));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> engine.map(source, mappings, "preserve", "drop"));
        assertEquals("Unknown transformation: unknown", exception.getMessage());
    }

    @Test
    void propagatesInvalidTransformationExceptionForDefaultValue() {
        MappingDefaultValue defaultValue = new MappingDefaultValue("string", "Fallback");
        List<MappingPlan> mappings = List.of(new MappingPlan("missing", "title", "unknown", defaultValue));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> engine.map(source, mappings, "omit", "drop"));
        assertEquals("Unknown transformation: unknown", exception.getMessage());
    }

    @Test
    void omitsLogicalNullWhenPolicyIsOmit() {
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("description", "details", "toInteger", null)), "omit", "drop");

        assertFalse(result.contains("details"));
        assertTrue(result.fields().isEmpty());
    }

    @Test
    void missingFieldIsOmittedWithOmitPolicy() {
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("missing", "absent", "unknown", null)), "omit", "drop");

        assertFalse(result.contains("absent"));
        assertTrue(result.fields().isEmpty());
    }

    @Test
    void defaultTakesPrecedenceOverOmitPolicyForLogicalNull() {
        MappingDefaultValue defaultValue = new MappingDefaultValue("string", "Fallback");
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("description", "details", null, defaultValue)), "omit", "drop");

        assertTrue(result.contains("details"));
        assertEquals(new LogicalValue.StringValue("Fallback"), result.get("details"));
    }

    @Test
    void defaultTakesPrecedenceForMissingField() {
        MappingDefaultValue defaultValue = new MappingDefaultValue("string", "Fallback");
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("missing", "title", null, defaultValue)), "omit", "drop");

        assertEquals(new LogicalValue.StringValue("Fallback"), result.get("title"));
    }

    @Test
    void transformsDefaultForLogicalNullWithOmitPolicy() {
        MappingDefaultValue defaultValue = new MappingDefaultValue("string", "25");
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("description", "quantity", "toInteger", defaultValue)), "omit", "drop");

        assertEquals(new LogicalValue.IntegerValue(25), result.get("quantity"));
    }

    @Test
    void nullPolicyDoesNotAffectNormalValues() {
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("numericText", "quantity", "toInteger", null)), "omit", "drop");

        assertEquals(new LogicalValue.IntegerValue(25), result.get("quantity"));
    }

    @Test
    void rejectsUnsupportedNullPolicy() {
        List<MappingPlan> mappings = List.of(new MappingPlan("name", "title", null, null));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> engine.map(source, mappings, "invalid", "drop"));

        assertEquals("Unsupported null policy: invalid", exception.getMessage());
    }

    @Test
    void rejectsUnsupportedNullPolicyWithNoMappings() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> engine.map(source, List.of(), "invalid", "drop"));

        assertEquals("Unsupported null policy: invalid", exception.getMessage());
    }

    @Test
    void rejectsUnsupportedUnmappedPolicy() {
        List<MappingPlan> mappings = List.of(new MappingPlan("name", "title", null, null));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> engine.map(source, mappings, "preserve", "invalid"));

        assertEquals("Unsupported unmapped policy: invalid", exception.getMessage());
    }

    @Test
    void rejectsUnsupportedUnmappedPolicyWithNoMappings() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> engine.map(source, List.of(), "preserve", "invalid"));

        assertEquals("Unsupported unmapped policy: invalid", exception.getMessage());
    }

    @Test
    void rejectsNullPolicy() {
        List<MappingPlan> mappings = List.of(new MappingPlan("name", "title", null, null));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> engine.map(source, mappings, null, "drop"));

        assertEquals("Unsupported null policy: null", exception.getMessage());
    }

    @Test
    void skipsTransformationForOmittedLogicalNull() {
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("description", "details", "unknown", null)), "omit", "drop");

        assertFalse(result.contains("details"));
        assertTrue(result.fields().isEmpty());
    }

    @Test
    void preservesLogicalNullWithUnknownTransformation() {
        LogicalRecord result = engine.map(source,
                List.of(new MappingPlan("description", "details", "unknown", null)), "preserve", "drop");

        assertTrue(result.contains("details"));
        assertSame(source.get("description"), result.get("details"));
    }
}
