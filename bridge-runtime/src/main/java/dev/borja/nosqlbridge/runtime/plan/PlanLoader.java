package dev.borja.nosqlbridge.runtime.plan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import tools.jackson.databind.ObjectMapper;

public class PlanLoader {

    private final ObjectMapper objectMapper;

    public PlanLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public BridgeExecutionPlan load(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            return objectMapper.readValue(input, BridgeExecutionPlan.class);
        }
    }
}
