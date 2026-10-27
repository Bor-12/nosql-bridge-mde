package dev.borja.nosqlbridge.runtime.work;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import dev.borja.nosqlbridge.runtime.plan.BridgeExecutionPlan;
import dev.borja.nosqlbridge.runtime.plan.ConnectorPlan;
import dev.borja.nosqlbridge.runtime.plan.MappingDefaultValue;
import dev.borja.nosqlbridge.runtime.plan.MappingPlan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkUnitMessageSerializerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final WorkUnitMessageSerializer serializer = new WorkUnitMessageSerializer(mapper);
    private final WorkUnit unit = new WorkUnit(
            new MigrationId(UUID.fromString("550e8400-e29b-41d4-a716-446655440000")), new WorkUnitId(2),
            new SourcePartition("mongodb-object-id-range", Map.of("lowerExclusive", "00000000000000000000001e",
                    "upperInclusive", "00000000000000000000003c")));

    @Test
    void restoresCompletePlanAndWorkUnitFromExistingFixture() throws Exception {
        BridgeExecutionPlan plan;
        try (InputStream input = getClass().getResourceAsStream("/plans/full-feature-bridge.json")) {
            assertNotNull(input);
            plan = mapper.readValue(input, BridgeExecutionPlan.class);
        }
        WorkUnitMessage message = new WorkUnitMessage(unit, plan);

        String json = serializer.serialize(message);
        WorkUnitMessage restored = serializer.deserialize(json);

        assertEquals(message, restored);
        assertEquals(unit.migrationId(), restored.workUnit().migrationId());
        assertEquals(unit.workUnitId(), restored.workUnit().workUnitId());
        assertEquals(unit.sourcePartition(), restored.workUnit().sourcePartition());
        assertEquals(plan.source(), restored.plan().source());
        assertEquals(plan.target(), restored.plan().target());
        assertEquals(plan.mappings(), restored.plan().mappings());
        assertEquals(plan.nullPolicy(), restored.plan().nullPolicy());
        assertEquals(plan.unmappedPolicy(), restored.plan().unmappedPolicy());
    }

    @Test
    void preservesHighPrecisionNumericDefaults() {
        BridgeExecutionPlan plan = new BridgeExecutionPlan(1, "test", new ConnectorPlan("source", Map.of()),
                new ConnectorPlan("target", Map.of()), "drop", "omit", List.of(new MappingPlan("value", "amount",
                        "toDecimal", new MappingDefaultValue("decimal", new BigDecimal("123456789.123456789123456789")))));
        WorkUnitMessage message = new WorkUnitMessage(unit, plan);

        assertEquals(message, serializer.deserialize(serializer.serialize(message)));
    }

    @Test
    void preservesMultibyteCharactersAndEscapedText() {
        WorkUnitMessage message = messageWithName("Migración € 😀\n\"\\");

        String json = serializer.serialize(message);

        assertEquals(message, serializer.deserialize(json));
    }

    @Test
    void roundTripsLargeMessagesWithoutTruncating() {
        WorkUnitMessage message = messageWithName("x".repeat(1_100_000));

        String json = serializer.serialize(message);

        assertEquals(message, serializer.deserialize(json));
    }

    @Test
    void rejectsNullInputsAndMapper() {
        assertThrows(IllegalArgumentException.class, () -> new WorkUnitMessageSerializer(null));
        assertThrows(IllegalArgumentException.class, () -> serializer.serialize(null));
        assertThrows(IllegalArgumentException.class, () -> serializer.deserialize(null));
        assertThrows(IllegalArgumentException.class, () -> serializer.deserialize("null"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{broken", "{}", "{\"workUnit\":null,\"plan\":null}", "[]"})
    void rejectsMalformedOrIncompleteMessages(String json) {
        assertThrows(JacksonException.class, () -> serializer.deserialize(json));
    }

    @Test
    void rejectsAdditionalTrailingJson() {
        String json = serializer.serialize(messageWithName("test"));

        assertThrows(JacksonException.class, () -> serializer.deserialize(json + " {}"));
    }

    private WorkUnitMessage messageWithName(String name) {
        return new WorkUnitMessage(unit, new BridgeExecutionPlan(1, name,
                new ConnectorPlan("mongodb", Map.of("database", "shop", "collection", "products")),
                new ConnectorPlan("dynamodb", Map.of("table", "Products", "partitionKey", "id")),
                "drop", "omit", List.of(new MappingPlan("_id", "id", "toString", null))));
    }
}
