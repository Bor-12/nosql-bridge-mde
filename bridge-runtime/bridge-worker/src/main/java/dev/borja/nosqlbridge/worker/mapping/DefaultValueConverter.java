package dev.borja.nosqlbridge.worker.mapping;

import java.math.BigDecimal;
import java.math.BigInteger;

import dev.borja.nosqlbridge.common.model.LogicalValue;
import dev.borja.nosqlbridge.common.plan.MappingDefaultValue;

public class DefaultValueConverter {

    public LogicalValue convert(MappingDefaultValue defaultValue) {
        if (defaultValue == null) {
            throw new IllegalArgumentException("Default value must not be null.");
        }
        if (defaultValue.type() == null) {
            throw new IllegalArgumentException("Unsupported default value type: null");
        }

        return switch (defaultValue.type()) {
            case "string" -> new LogicalValue.StringValue(convertString(defaultValue.value()));
            case "integer" -> new LogicalValue.IntegerValue(convertInteger(defaultValue.value()));
            case "decimal" -> new LogicalValue.DecimalValue(convertDecimal(defaultValue.value()));
            case "boolean" -> new LogicalValue.BooleanValue(convertBoolean(defaultValue.value()));
            default -> throw new IllegalArgumentException("Unsupported default value type: " + defaultValue.type());
        };
    }

    private String convertString(Object value) {
        if (value instanceof String) {
            return (String) value;
        }
        throw incompatibleValue("string", value);
    }

    private long convertInteger(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            Number number = (Number) value;
            return number.longValue();
        }
        if (value instanceof BigInteger) {
            BigInteger integer = (BigInteger) value;
            try {
                return integer.longValueExact();
            } catch (ArithmeticException exception) {
                throw incompatibleValue("integer", value);
            }
        }
        if (value instanceof BigDecimal) {
            BigDecimal decimal = (BigDecimal) value;
            try {
                return decimal.longValueExact();
            } catch (ArithmeticException exception) {
                throw incompatibleValue("integer", value);
            }
        }
        if (value instanceof Float || value instanceof Double) {
            try {
                return finiteBigDecimal(value).longValueExact();
            } catch (ArithmeticException exception) {
                throw incompatibleValue("integer", value);
            }
        }
        throw incompatibleValue("integer", value);
    }

    private BigDecimal convertDecimal(Object value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof BigInteger) {
            BigInteger integer = (BigInteger) value;
            return new BigDecimal(integer);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            Number number = (Number) value;
            return BigDecimal.valueOf(number.longValue());
        }
        if (value instanceof Float || value instanceof Double) {
            return finiteBigDecimal(value);
        }
        throw incompatibleValue("decimal", value);
    }

    private boolean convertBoolean(Object value) {
        if (value instanceof Boolean) {
            Boolean bool = (Boolean) value;
            return bool.booleanValue();
        }
        throw incompatibleValue("boolean", value);
    }

    private BigDecimal finiteBigDecimal(Object value) {
        Number number = (Number) value;
        double doubleValue = number.doubleValue();
        if (!Double.isFinite(doubleValue)) {
            throw incompatibleValue("numeric", value);
        }
        return BigDecimal.valueOf(doubleValue);
    }

    private IllegalArgumentException incompatibleValue(String type, Object value) {
        String valueType = value == null ? "null" : value.getClass().getSimpleName();
        return new IllegalArgumentException("Default value of type " + type
                + " is incompatible with value type " + valueType + ".");
    }
}
