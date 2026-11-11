package dev.borja.nosqlbridge.common.work;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionContractTest {
    @Test
    void readsAndRoundTripsTheFixtureAlsoCheckedByTheTypeScriptCompiler() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (var input = getClass().getResourceAsStream("/contracts/work-unit-v1.json")) {
            assertNotNull(input);
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            WorkUnitMessageSerializer serializer = new WorkUnitMessageSerializer(mapper);
            var message = serializer.deserialize(json);
            assertEquals(1, message.plan().planVersion());
            assertEquals(0, message.workUnit().workUnitId().value());
            assertEquals(message, serializer.deserialize(serializer.serialize(message)));
        }
    }

    @Test
    void emitsVersionedMachineReadableEvidenceWithoutPlanOrConnections() {
        PrintStream previous = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            ExecutionEvents.emit("ACKNOWLEDGED", "550e8400-e29b-41d4-a716-446655440000", Map.of("workUnitId", 0));
        } finally { System.setOut(previous); }
        String line = bytes.toString(StandardCharsets.UTF_8).trim();
        assertTrue(line.startsWith("BRIDGE_EVENT "));
        var event = new ObjectMapper().readTree(line.substring("BRIDGE_EVENT ".length()));
        assertEquals(1, event.get("eventVersion").asInt());
        assertEquals("ACKNOWLEDGED", event.get("type").asText());
        assertEquals(0, event.get("workUnitId").asInt());
        assertFalse(event.has("plan"));
    }
}
