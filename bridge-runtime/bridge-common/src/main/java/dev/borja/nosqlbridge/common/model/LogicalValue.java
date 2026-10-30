package dev.borja.nosqlbridge.common.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public sealed interface LogicalValue {

    record StringValue(String value) implements LogicalValue {
    }

    record IntegerValue(long value) implements LogicalValue {
    }

    record DecimalValue(BigDecimal value) implements LogicalValue {
    }

    record BooleanValue(boolean value) implements LogicalValue {
    }

    record BinaryValue(byte[] value) implements LogicalValue {
        public BinaryValue {
            value = value.clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }
    }

    record TimestampValue(Instant value) implements LogicalValue {
    }

    record ObjectValue(Map<String, LogicalValue> value) implements LogicalValue {

        public ObjectValue {
            value = Map.copyOf(value);
        }
    }

    record ArrayValue(List<LogicalValue> value) implements LogicalValue {

        public ArrayValue {
            value = List.copyOf(value);
        }
    }

    record NullValue() implements LogicalValue {
    }


}
