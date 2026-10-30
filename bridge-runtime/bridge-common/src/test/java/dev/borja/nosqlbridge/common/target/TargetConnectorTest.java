package dev.borja.nosqlbridge.common.target;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.borja.nosqlbridge.common.model.LogicalRecord;
import dev.borja.nosqlbridge.common.model.LogicalValue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TargetConnectorTest {

    @Test
    void defaultBatchDelegatesInOrderIncludingRepeatedKeys() {
        List<LogicalRecord> written = new ArrayList<>();
        TargetConnector connector = written::add;
        LogicalRecord first = new LogicalRecord(Map.of("id", new LogicalValue.StringValue("A")));
        LogicalRecord second = new LogicalRecord(Map.of("id", new LogicalValue.StringValue("B")));

        connector.writeBatch(List.of(first, second, first));

        assertEquals(List.of(first, second, first), written);
    }

    @Test
    void emptyBatchDoesNotWrite() {
        TargetConnector connector = record -> { throw new AssertionError("Must not write."); };

        connector.writeBatch(List.of());
    }

    @Test
    void rejectsNullBatchAndRecord() {
        TargetConnector connector = record -> { throw new AssertionError("Must not write."); };

        assertThrows(IllegalArgumentException.class, () -> connector.writeBatch(null));
        assertThrows(IllegalArgumentException.class, () -> connector.writeBatch(Arrays.asList((LogicalRecord) null)));
    }

    @Test
    void propagatesFailureInsteadOfClaimingBatchSuccess() {
        IllegalStateException failure = new IllegalStateException("write failed");
        TargetConnector connector = record -> { throw failure; };

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> connector.writeBatch(List.of(new LogicalRecord(Map.of())))));
    }
}
