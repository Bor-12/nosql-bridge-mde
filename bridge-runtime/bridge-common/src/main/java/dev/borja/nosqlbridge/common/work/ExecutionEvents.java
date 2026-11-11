package dev.borja.nosqlbridge.common.work;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** Versioned, secret-free stdout protocol. Logs are not a durable distributed status store. */
public final class ExecutionEvents {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ExecutionEvents() {}

    public static synchronized void emit(String type, String migrationId, Map<String, Object> fields) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventVersion", 1);
        event.put("type", type);
        if (migrationId != null) event.put("migrationId", migrationId);
        event.putAll(fields);
        System.out.println("BRIDGE_EVENT " + MAPPER.writeValueAsString(event));
        System.out.flush();
    }
}
