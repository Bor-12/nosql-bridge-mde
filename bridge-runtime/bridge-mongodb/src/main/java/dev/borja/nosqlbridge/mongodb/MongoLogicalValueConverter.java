package dev.borja.nosqlbridge.mongodb;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

import dev.borja.nosqlbridge.common.model.LogicalRecord;
import dev.borja.nosqlbridge.common.model.LogicalValue;

public class MongoLogicalValueConverter {

    public LogicalRecord toLogicalRecord(Document document) {
        if (document == null) {
            throw new IllegalArgumentException("MongoDB document must not be null.");
        }
        return new LogicalRecord(toLogicalFields(document));
    }

    private Map<String, LogicalValue> toLogicalFields(Document document) {
        Map<String, LogicalValue> fields = new HashMap<>();
        for (Map.Entry<String, Object> field : document.entrySet()) {
            fields.put(field.getKey(), toLogicalValue(field.getValue()));
        }
        return fields;
    }

    private LogicalValue toLogicalValue(Object value) {
        if (value == null) {
            return new LogicalValue.NullValue();
        }
        if (value instanceof String) {
            return new LogicalValue.StringValue((String) value);
        }
        if (value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte) {
            return new LogicalValue.IntegerValue(((Number) value).longValue());
        }
        if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException("Non-finite MongoDB numeric value: " + value);
            }
            return new LogicalValue.DecimalValue(BigDecimal.valueOf(number));
        }
        if (value instanceof BigDecimal) {
            return new LogicalValue.DecimalValue((BigDecimal) value);
        }
        if (value instanceof Decimal128) {
            Decimal128 decimal = (Decimal128) value;
            if (!decimal.isFinite()) {
                throw new IllegalArgumentException("Non-finite MongoDB numeric value: " + value);
            }
            return new LogicalValue.DecimalValue(decimal.bigDecimalValue());
        }
        if (value instanceof Boolean) {
            return new LogicalValue.BooleanValue((Boolean) value);
        }
        if (value instanceof byte[]) {
            return new LogicalValue.BinaryValue((byte[]) value);
        }
        if (value instanceof Binary) {
            return new LogicalValue.BinaryValue(((Binary) value).getData());
        }
        if (value instanceof Date) {
            return new LogicalValue.TimestampValue(((Date) value).toInstant());
        }
        if (value instanceof ObjectId) {
            return new LogicalValue.StringValue(((ObjectId) value).toHexString());
        }
        if (value instanceof Document) {
            return new LogicalValue.ObjectValue(toLogicalFields((Document) value));
        }
        if (value instanceof List<?>) {
            List<LogicalValue> elements = new ArrayList<>();
            for (Object element : (List<?>) value) {
                elements.add(toLogicalValue(element));
            }
            return new LogicalValue.ArrayValue(elements);
        }
        throw new IllegalArgumentException("Unsupported MongoDB value type: " + value.getClass().getName());
    }
}
