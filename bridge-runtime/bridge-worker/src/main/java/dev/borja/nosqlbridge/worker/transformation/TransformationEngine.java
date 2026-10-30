package dev.borja.nosqlbridge.worker.transformation;

import java.math.BigDecimal;
import java.util.Base64;

import dev.borja.nosqlbridge.common.model.LogicalValue;

public class TransformationEngine {

    public LogicalValue transform(String transformation, LogicalValue value) {
        if (transformation == null || value instanceof LogicalValue.NullValue) {
            return value;
        }

        return switch (transformation) {
            case "toString" -> toStringValue(value);
            case "toInteger" -> toIntegerValue(value);
            case "toDecimal" -> toDecimalValue(value);
            default -> throw new IllegalArgumentException("Unknown transformation: " + transformation);
        };
    }

    private LogicalValue toStringValue(LogicalValue value) {
        if (value instanceof LogicalValue.StringValue) {
            return value;
        }
        if (value instanceof LogicalValue.IntegerValue) {
            LogicalValue.IntegerValue integer = (LogicalValue.IntegerValue) value;
            return new LogicalValue.StringValue(Long.toString(integer.value()));
        }
        if (value instanceof LogicalValue.DecimalValue) {
            LogicalValue.DecimalValue decimal = (LogicalValue.DecimalValue) value;
            return new LogicalValue.StringValue(decimal.value().toString());
        }
        if (value instanceof LogicalValue.BooleanValue) {
            LogicalValue.BooleanValue bool = (LogicalValue.BooleanValue) value;
            return new LogicalValue.StringValue(Boolean.toString(bool.value()));
        }
        if (value instanceof LogicalValue.BinaryValue) {
            LogicalValue.BinaryValue binary = (LogicalValue.BinaryValue) value;
            return new LogicalValue.StringValue(Base64.getEncoder().encodeToString(binary.value()));
        }
        if (value instanceof LogicalValue.TimestampValue) {
            LogicalValue.TimestampValue timestamp = (LogicalValue.TimestampValue) value;
            return new LogicalValue.StringValue(timestamp.value().toString());
        }
        throw unsupportedType("toString", value);
    }

    private LogicalValue toIntegerValue(LogicalValue value) {
        if (value instanceof LogicalValue.StringValue) {
            LogicalValue.StringValue string = (LogicalValue.StringValue) value;
            return new LogicalValue.IntegerValue(Long.parseLong(string.value()));
        }
        if (value instanceof LogicalValue.IntegerValue) {
            return value;
        }
        if (value instanceof LogicalValue.DecimalValue) {
            LogicalValue.DecimalValue decimal = (LogicalValue.DecimalValue) value;
            return new LogicalValue.IntegerValue(decimal.value().longValueExact());
        }
        throw unsupportedType("toInteger", value);
    }

    private LogicalValue toDecimalValue(LogicalValue value) {
        if (value instanceof LogicalValue.StringValue) {
            LogicalValue.StringValue string = (LogicalValue.StringValue) value;
            return new LogicalValue.DecimalValue(new BigDecimal(string.value()));
        }
        if (value instanceof LogicalValue.IntegerValue) {
            LogicalValue.IntegerValue integer = (LogicalValue.IntegerValue) value;
            return new LogicalValue.DecimalValue(BigDecimal.valueOf(integer.value()));
        }
        if (value instanceof LogicalValue.DecimalValue) {
            return value;
        }
        throw unsupportedType("toDecimal", value);
    }

    private IllegalArgumentException unsupportedType(String transformation, LogicalValue value) {
        return new IllegalArgumentException("Transformation " + transformation
                + " does not support " + value.getClass().getSimpleName() + ".");
    }
}
