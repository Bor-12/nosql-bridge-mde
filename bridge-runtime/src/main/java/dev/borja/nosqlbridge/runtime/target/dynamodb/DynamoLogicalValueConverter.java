package dev.borja.nosqlbridge.runtime.target.dynamodb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import dev.borja.nosqlbridge.runtime.model.LogicalRecord;
import dev.borja.nosqlbridge.runtime.model.LogicalValue;

public class DynamoLogicalValueConverter {

    public Map<String, AttributeValue> toAttributeMap(LogicalRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("Logical record must not be null.");
        }
        return toAttributes(record.fields());
    }

    private Map<String, AttributeValue> toAttributes(Map<String, LogicalValue> fields) {
        Map<String, AttributeValue> attributes = new HashMap<>();
        for (Map.Entry<String, LogicalValue> field : fields.entrySet()) {
            attributes.put(field.getKey(), toAttributeValue(field.getValue()));
        }
        return attributes;
    }

    private AttributeValue toAttributeValue(LogicalValue value) {
        return switch (value) {
            case LogicalValue.StringValue v -> AttributeValue.builder().s(v.value()).build();
            case LogicalValue.IntegerValue v -> AttributeValue.builder().n(Long.toString(v.value())).build();
            case LogicalValue.DecimalValue v -> AttributeValue.builder().n(v.value().toPlainString()).build();
            case LogicalValue.BooleanValue v -> AttributeValue.builder().bool(v.value()).build();
            case LogicalValue.BinaryValue v -> AttributeValue.builder().b(SdkBytes.fromByteArray(v.value())).build();
            case LogicalValue.NullValue v -> AttributeValue.builder().nul(true).build();
            case LogicalValue.ObjectValue v -> AttributeValue.builder().m(toAttributes(v.value())).build();
            case LogicalValue.ArrayValue v -> convertArray(v);
            case LogicalValue.TimestampValue v -> throw new IllegalArgumentException(
                    "TimestampValue is not supported directly by DynamoDB; transform it before writing.");
        };
    }
    private AttributeValue convertArray(LogicalValue.ArrayValue v) {
        List<AttributeValue> elements = new ArrayList<>();

        for (LogicalValue element : v.value()) {
            elements.add(toAttributeValue(element));
        }

        return AttributeValue.builder().l(elements).build();
    }
}
