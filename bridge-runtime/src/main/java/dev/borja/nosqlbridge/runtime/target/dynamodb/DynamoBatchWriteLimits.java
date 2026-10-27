package dev.borja.nosqlbridge.runtime.target.dynamodb;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.json.JsonWriteFeature;

/** Storage estimates and JSON wire sizes for attributes emitted by our converter. */
final class DynamoBatchWriteLimits {

    static final int MAX_OPERATIONS = 25;
    static final long MAX_ITEM_BYTES = 400L * 1024;
    static final long MAX_REQUEST_BYTES = 16L * 1024 * 1024;
    // Match the SDK's escaped surrogate pairs rather than four-byte UTF-8 code points.
    private static final JsonFactory JSON = JsonFactory.builder()
            .disable(JsonWriteFeature.COMBINE_UNICODE_SURROGATES_IN_UTF8).build();

    private DynamoBatchWriteLimits() {}

    static long itemBytes(Map<String, AttributeValue> attributes) {
        long size = 0;
        for (Map.Entry<String, AttributeValue> entry : attributes.entrySet()) {
            size += utf8Bytes(entry.getKey()) + storedValueBytes(entry.getValue());
        }
        return size;
    }

    private static long storedValueBytes(AttributeValue value) {
        if (value.s() != null) return utf8Bytes(value.s());
        // AWS documents approximately one byte per two significant digits + 1.
        // Reserve an additional byte instead of treating text length as storage.
        if (value.n() != null) {
            int digits = new BigDecimal(value.n()).stripTrailingZeros().precision();
            return (digits + 1L) / 2 + 2;
        }
        if (value.b() != null) return value.b().asByteBuffer().remaining();
        if (value.bool() != null || value.nul() != null) return 1;
        if (value.hasM()) return 3 + value.m().size() + itemBytes(value.m());
        if (value.hasL()) {
            long size = 3;
            for (AttributeValue element : value.l()) size += 1 + storedValueBytes(element);
            return size;
        }
        throw new IllegalArgumentException("Unsupported DynamoDB attribute in batch sizing.");
    }

    static long envelopeBytes(String table) {
        ByteCounter output = new ByteCounter();
        try (JsonGenerator json = JSON.createGenerator(ObjectWriteContext.empty(), output)) {
            json.writeStartObject();
            json.writeObjectPropertyStart("RequestItems");
            json.writeArrayPropertyStart(table);
            json.writeEndArray();
            json.writeEndObject();
            json.writeEndObject();
        }
        return output.bytes;
    }

    static long putBytes(Map<String, AttributeValue> attributes) {
        ByteCounter output = new ByteCounter();
        try (JsonGenerator json = JSON.createGenerator(ObjectWriteContext.empty(), output)) {
            json.writeStartObject();
            json.writeObjectPropertyStart("PutRequest");
            json.writeName("Item");
            writeAttributes(json, attributes);
            json.writeEndObject();
            json.writeEndObject();
        }
        return output.bytes;
    }

    private static void writeAttributes(JsonGenerator json, Map<String, AttributeValue> attributes) {
        json.writeStartObject();
        for (Map.Entry<String, AttributeValue> entry : attributes.entrySet()) {
            json.writeName(entry.getKey());
            writeValue(json, entry.getValue());
        }
        json.writeEndObject();
    }

    private static void writeValue(JsonGenerator json, AttributeValue value) {
        json.writeStartObject();
        if (value.s() != null) {
            json.writeStringProperty("S", value.s());
        } else if (value.n() != null) {
            json.writeStringProperty("N", value.n());
        } else if (value.b() != null) {
            json.writeName("B");
            json.writeBinary(value.b().asInputStream(), value.b().asByteBuffer().remaining());
        } else if (value.bool() != null) {
            json.writeBooleanProperty("BOOL", value.bool());
        } else if (value.nul() != null) {
            json.writeBooleanProperty("NULL", value.nul());
        } else if (value.hasM()) {
            json.writeName("M");
            writeAttributes(json, value.m());
        } else if (value.hasL()) {
            json.writeArrayPropertyStart("L");
            for (AttributeValue element : value.l()) writeValue(json, element);
            json.writeEndArray();
        } else {
            throw new IllegalArgumentException("Unsupported DynamoDB attribute in batch sizing.");
        }
        json.writeEndObject();
    }

    private static long utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static final class ByteCounter extends OutputStream {
        private long bytes;

        @Override
        public void write(int value) {
            bytes++;
        }

        @Override
        public void write(byte[] buffer, int offset, int length) {
            bytes += length;
        }
    }
}
