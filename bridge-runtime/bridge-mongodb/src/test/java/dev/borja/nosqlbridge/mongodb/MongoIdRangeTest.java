package dev.borja.nosqlbridge.mongodb;

import java.util.Map;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.RawBsonDocument;
import org.bson.codecs.BsonDocumentCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import dev.borja.nosqlbridge.common.work.SourcePartition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class MongoIdRangeTest {

    private static final String LOW = "000000000000000000000000";
    private static final String HIGH = "ffffffffffffffffffffffff";

    @Test
    void canonicalBoundsPreserveEveryBsonTypeAndBuildLiteralExpressions() {
        BsonDocument values = BsonDocument.parse("{i: {$numberInt: '1'}, l: {$numberLong: '2'}, "
                + "d: {$numberDouble: '3.5'}, n: {$numberDecimal: '4.50'}, s: '$price', "
                + "o: {$oid: '000000000000000000000001'}, z: null, b: true, "
                + "date: {$date: {$numberLong: '123'}}, t: {$timestamp: {t: 12, i: 3}}, "
                + "bin: {$binary: {base64: 'AQID', subType: '80'}}, doc: {a: 1, b: 'x'}, "
                + "min: {$minKey: 1}, max: {$maxKey: 1}}");
        ObjectMapper mapper = new ObjectMapper();
        for (BsonValue value : values.values()) {
            MongoIdRange range = MongoIdRange.fromBson(null, value);
            SourcePartition descriptor = range.toSourcePartition();
            SourcePartition restored = mapper.readValue(mapper.writeValueAsString(descriptor), SourcePartition.class);
            assertEquals(range, MongoIdRange.fromSourcePartition(restored));
            assertEquals(bsonBytes(value), bsonBytes(BsonDocument.parse(range.upperInclusive()).get("value")));
            assertEquals(new Document("$expr", new Document("$lte", java.util.List.of("$_id", new Document("$literal", value)))),
                    MongoIdRange.fromSourcePartition(restored).toFilter());
        }
    }

    @Test
    void preservesCompoundIdFieldOrderAndNestedNumericTypesAcrossDescriptorJson() {
        BsonDocument first = BsonDocument.parse("{a: {$numberInt: '1'}, b: {y: {$numberLong: '2'}, x: [{$numberDouble: '3.0'}, {$numberDecimal: '4.00'}]}}");
        BsonDocument reordered = new BsonDocument("b", first.get("b")).append("a", first.get("a"));
        assertEquals(first, reordered);
        assertNotEquals(bsonBytes(first), bsonBytes(reordered));
        ObjectMapper mapper = new ObjectMapper();
        for (BsonDocument value : java.util.List.of(first, reordered)) {
            SourcePartition descriptor = MongoIdRange.fromBson(null, value).toSourcePartition();
            SourcePartition restored = mapper.readValue(mapper.writeValueAsString(descriptor), SourcePartition.class);
            MongoIdRange range = MongoIdRange.fromSourcePartition(restored);
            assertEquals(bsonBytes(value), bsonBytes(BsonDocument.parse(range.upperInclusive()).get("value")));
        }
    }

    @Test
    void rejectsDocumentsThatExtendedJsonWouldInterpretAsAnotherBsonType() {
        BsonDocument value = new BsonDocument("$numberInt", new org.bson.BsonString("1"));
        assertThrows(IllegalArgumentException.class, () -> MongoIdRange.fromBson(null, value));
    }

    private java.nio.ByteBuffer bsonBytes(BsonValue value) {
        return new RawBsonDocument(new BsonDocument("value", value), new BsonDocumentCodec()).getByteBuffer().asNIO();
    }

    @Test
    void buildsCrossTypeRangeWithoutQueryTypeBracketing() {
        BsonValue lower = new org.bson.BsonString("TFG-E2E-001");
        BsonValue upper = new org.bson.BsonObjectId(new ObjectId(HIGH));
        MongoIdRange range = MongoIdRange.fromBson(lower, upper);
        assertEquals(new Document("$expr", new Document("$and", java.util.List.of(
                new Document("$gt", java.util.List.of("$_id", new Document("$literal", lower))),
                new Document("$lte", java.util.List.of("$_id", new Document("$literal", upper)))))), range.toFilter());
    }

    @Test
    void rejectsUnsafeOrMalformedBsonBounds() {
        for (BsonValue value : java.util.List.of(new org.bson.BsonArray(), new org.bson.BsonRegularExpression("x"),
                new org.bson.BsonUndefined())) {
            assertThrows(IllegalArgumentException.class, () -> MongoIdRange.fromBson(null, value));
        }
        assertThrows(IllegalArgumentException.class, () -> MongoIdRange.fromBson(null, null));
        for (String json : java.util.List.of("broken", "{}", "{other: 1}", "{value: 1, extra: 2}", "{value: []}")) {
            assertThrows(IllegalArgumentException.class, () -> MongoIdRange.fromSourcePartition(
                    new SourcePartition(MongoIdRange.BSON_TYPE, Map.of("upperInclusive", json))));
        }
    }

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
    void preservesStringBoundsAndTheirBsonTypeAcrossJson() {
        ObjectMapper mapper = new ObjectMapper();
        for (MongoIdRange range : new MongoIdRange[] {
                new MongoIdRange(null, "", MongoIdRange.STRING_TYPE),
                new MongoIdRange("", "000000000000000000000001", MongoIdRange.STRING_TYPE),
                new MongoIdRange("TFG-E2E-001", "TFG-E2E-003", MongoIdRange.STRING_TYPE),
                new MongoIdRange("\uE000", "\uD800\uDC00", MongoIdRange.STRING_TYPE),
                new MongoIdRange(null, "é\n\"\\", MongoIdRange.STRING_TYPE)}) {
            SourcePartition restored = mapper.readValue(mapper.writeValueAsString(range.toSourcePartition()), SourcePartition.class);
            MongoIdRange decoded = MongoIdRange.fromSourcePartition(restored);
            assertEquals(range, decoded);
            Document bounds = new Document("$lte", range.upperInclusive());
            if (range.lowerExclusive() != null) {
                bounds.append("$gt", range.lowerExclusive());
            }
            assertEquals(new Document("_id", bounds), decoded.toFilter());
        }
    }

    @Test
    void rejectsMalformedStringDescriptorsAndReversedBounds() {
        assertThrows(IllegalArgumentException.class, () -> new MongoIdRange(null, null, MongoIdRange.STRING_TYPE));
        assertThrows(IllegalArgumentException.class, () -> new MongoIdRange("a", "a", MongoIdRange.STRING_TYPE));
        assertThrows(IllegalArgumentException.class, () -> new MongoIdRange("b", "a", MongoIdRange.STRING_TYPE));
        assertThrows(IllegalArgumentException.class, () -> new MongoIdRange(null, "a", "unknown"));
        assertThrows(IllegalArgumentException.class, () -> MongoIdRange.fromSourcePartition(
                new SourcePartition(MongoIdRange.STRING_TYPE, Map.of())));
        assertThrows(IllegalArgumentException.class, () -> MongoIdRange.fromSourcePartition(
                new SourcePartition(MongoIdRange.STRING_TYPE, Map.of("upperInclusive", "a", "extra", "b"))));
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
