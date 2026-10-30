package dev.borja.nosqlbridge.common.work;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;

public class WorkUnitMessageSerializer {

    private final ObjectWriter messageWriter;
    private final ObjectReader messageReader;

    public WorkUnitMessageSerializer(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("Object mapper must not be null.");
        }
        this.messageWriter = objectMapper.writerFor(WorkUnitMessage.class)
                .without(SerializationFeature.INDENT_OUTPUT);
        this.messageReader = objectMapper.readerFor(WorkUnitMessage.class)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public String serialize(WorkUnitMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("Work unit message must not be null.");
        }
        return messageWriter.writeValueAsString(message);
    }

    public WorkUnitMessage deserialize(String json) {
        if (json == null) {
            throw new IllegalArgumentException("Message JSON must not be null.");
        }
        WorkUnitMessage message = messageReader.readValue(json);
        if (message == null) {
            throw new IllegalArgumentException("Work unit message must not be null.");
        }
        return message;
    }

}
