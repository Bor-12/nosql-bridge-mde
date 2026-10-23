package dev.borja.nosqlbridge.runtime.source.mongodb;

import java.util.Map;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import dev.borja.nosqlbridge.runtime.work.SourcePartition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MongoIdRangeTest {

    private static final String LOW = "000000000000000000000000";
    private static final String HIGH = "ffffffffffffffffffffffff";

    @Test
    void translatesFirstRangeToInclusiveUpperBoundFilter() {
        MongoIdRange range = new MongoIdRange(null, LOW);

        assertEquals(new Document("_id", new Document("$lte", new ObjectId(LOW))), range.toFilter());
        assertEquals(Map.of("upperInclusive", LOW), range.toSourcePartition().parameters());
    }

    @Test
    void translatesBoundedRangeToExclusiveLowerAndInclusiveUpperFilter() {
        MongoIdRange range = new MongoIdRange(LOW, HIGH);

        assertEquals(new Document("_id", new Document("$gt", new ObjectId(LOW))
                .append("$lte", new ObjectId(HIGH))), range.toFilter());
    }

    @Test
    void canonicalizesHexStrings() {
        assertEquals(new MongoIdRange(LOW, HIGH), new MongoIdRange(LOW, HIGH.toUpperCase(java.util.Locale.ROOT)));
    }

    @Test
    void preservesFirstAndBoundedRangesAcrossDescriptorJson() {
        ObjectMapper mapper = new ObjectMapper();
        for (MongoIdRange range : new MongoIdRange[] {new MongoIdRange(null, LOW), new MongoIdRange(LOW, HIGH)}) {
            SourcePartition descriptor = range.toSourcePartition();
            SourcePartition restored = mapper.readValue(mapper.writeValueAsString(descriptor), SourcePartition.class);

            assertEquals(descriptor, restored);
            assertEquals(range, MongoIdRange.fromSourcePartition(restored));
            assertEquals(range.toFilter(), MongoIdRange.fromSourcePartition(restored).toFilter());
        }
    }

    @Test
    void rejectsInvalidOrEmptyRanges() {
        assertThrows(IllegalArgumentException.class, () -> new MongoIdRange(null, null));
        assertThrows(IllegalArgumentException.class, () -> new MongoIdRange(null, "invalid"));
        assertThrows(IllegalArgumentException.class, () -> new MongoIdRange("", HIGH));
        assertThrows(IllegalArgumentException.class, () -> new MongoIdRange(LOW, LOW));
        assertThrows(IllegalArgumentException.class, () -> new MongoIdRange(HIGH, LOW));
    }

    @Test
    void rejectsWrongOrMalformedDescriptors() {
        assertThrows(IllegalArgumentException.class, () -> MongoIdRange.fromSourcePartition(null));
        assertThrows(IllegalArgumentException.class,
                () -> MongoIdRange.fromSourcePartition(new SourcePartition("other-range", Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> MongoIdRange.fromSourcePartition(new SourcePartition(MongoIdRange.TYPE, Map.of())));
        assertThrows(IllegalArgumentException.class, () -> MongoIdRange.fromSourcePartition(
                new SourcePartition(MongoIdRange.TYPE, Map.of("upperInclusive", HIGH, "typo", LOW))));
        assertThrows(IllegalArgumentException.class, () -> MongoIdRange.fromSourcePartition(
                new SourcePartition(MongoIdRange.TYPE, Map.of("upperInclusive", "invalid"))));
        assertThrows(IllegalArgumentException.class, () -> MongoIdRange.fromSourcePartition(
                new SourcePartition(MongoIdRange.TYPE, Map.of("lowerExclusive", "invalid", "upperInclusive", HIGH))));
        assertThrows(IllegalArgumentException.class, () -> MongoIdRange.fromSourcePartition(
                new SourcePartition(MongoIdRange.TYPE, Map.of("lowerExclusive", LOW, "upperInclusive", LOW))));
        assertThrows(IllegalArgumentException.class, () -> MongoIdRange.fromSourcePartition(
                new SourcePartition(MongoIdRange.TYPE, Map.of("lowerExclusive", HIGH, "upperInclusive", LOW))));
    }
}
