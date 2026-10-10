package dev.borja.nosqlbridge.runtime.model;

public class LogicalPathResolver {

    public LogicalValue resolve(LogicalRecord record, String path) {
        String[] fields = path.split("\\.");

        LogicalValue currentValue = record.get(fields[0]);

        for (int i = 1; i < fields.length; i++) {
            if (!(currentValue instanceof LogicalValue.ObjectValue)) {
                return null;
            }

            LogicalValue.ObjectValue objectValue =
                    (LogicalValue.ObjectValue) currentValue;

            currentValue = objectValue.value().get(fields[i]);
        }

        return currentValue;
    }
}