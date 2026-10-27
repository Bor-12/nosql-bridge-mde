package dev.borja.nosqlbridge.runtime.work;

import java.util.Map;

public record SourcePartition(String type, Map<String, String> parameters) {

    public SourcePartition {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Source partition type must not be null or blank.");
        }
        if (parameters == null) {
            throw new IllegalArgumentException("Source partition parameters must not be null.");
        }
        parameters = Map.copyOf(parameters);
    }
}
