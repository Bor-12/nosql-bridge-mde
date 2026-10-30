package dev.borja.nosqlbridge.common.model;

import java.util.Map;

public record LogicalRecord(Map<String, LogicalValue> fields) {

    public LogicalRecord {
        fields = Map.copyOf(fields);
    }

    public LogicalValue get(String name) {
        return fields.get(name);
    }

    public boolean contains(String name) {
        return fields.containsKey(name);
    }
}
