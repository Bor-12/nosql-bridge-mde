package dev.borja.nosqlbridge.runtime.connector.dynamodb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.borja.nosqlbridge.runtime.model.LogicalRecord;
import dev.borja.nosqlbridge.runtime.model.LogicalValue;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

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
        if (value instanceof LogicalValue.StringValue) {
            return AttributeValue.builder().s(((LogicalValue.StringValue) value).value()).build();
        }
        if (value instanceof LogicalValue.IntegerValue) {
            long number = ((LogicalValue.IntegerValue) value).value();
            return AttributeValue.builder().n(Long.toString(number)).build();
        }
        if (value instanceof LogicalValue.DecimalValue) {
            return AttributeValue.builder().n(((LogicalValue.DecimalValue) value).value().toPlainString()).build();
        }
        if (value instanceof LogicalValue.BooleanValue) {
            return AttributeValue.builder().bool(((LogicalValue.BooleanValue) value).value()).build();
        }
        if (value instanceof LogicalValue.BinaryValue) {
            SdkBytes bytes = SdkBytes.fromByteArray(((LogicalValue.BinaryValue) value).value());
            return AttributeValue.builder().b(bytes).build();
        }
        if (value instanceof LogicalValue.NullValue) {
            return AttributeValue.builder().nul(true).build();
        }
        if (value instanceof LogicalValue.ObjectValue) {
            return AttributeValue.builder().m(toAttributes(((LogicalValue.ObjectValue) value).value())).build();
        }
        if (value instanceof LogicalValue.ArrayValue) {
            List<AttributeValue> elements = new ArrayList<>();
            for (LogicalValue element : ((LogicalValue.ArrayValue) value).value()) {
                elements.add(toAttributeValue(element));
            }
            return AttributeValue.builder().l(elements).build();
        }
        if (value instanceof LogicalValue.TimestampValue) {
            throw new IllegalArgumentException(
                    "TimestampValue is not supported directly by DynamoDB; transform it before writing.");
        }
        throw new IllegalArgumentException("Unsupported DynamoDB logical value type: " + value.getClass().getName());
    }
}
