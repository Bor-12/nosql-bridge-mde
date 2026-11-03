package dev.borja.nosqlbridge.mongodb;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.List;

import org.bson.Document;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.BsonType;
import org.bson.RawBsonDocument;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.bson.types.ObjectId;

import dev.borja.nosqlbridge.common.work.SourcePartition;

public record MongoIdRange(String lowerExclusive, String upperInclusive, String type) {

    public static final String TYPE = "mongodb-object-id-range";
    public static final String STRING_TYPE = "mongodb-string-id-range";
    public static final String BSON_TYPE = "mongodb-bson-id-range";
    private static final JsonWriterSettings JSON_SETTINGS = JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build();
    private static final String LOWER_EXCLUSIVE = "lowerExclusive";
    private static final String UPPER_INCLUSIVE = "upperInclusive";
    private static final Set<String> PARAMETERS = Set.of(LOWER_EXCLUSIVE, UPPER_INCLUSIVE);

    public MongoIdRange(String lowerExclusive, String upperInclusive) {
        this(lowerExclusive, upperInclusive, TYPE);
    }

    public MongoIdRange {
        if (!TYPE.equals(type) && !STRING_TYPE.equals(type) && !BSON_TYPE.equals(type)) {
            throw new IllegalArgumentException("Unsupported MongoDB id range type.");
        }
        if (upperInclusive == null) {
            throw new IllegalArgumentException("Upper bound must not be null.");
        }
        if (TYPE.equals(type)) {
            if (!ObjectId.isValid(upperInclusive)) {
                throw new IllegalArgumentException("Upper bound must be an ObjectId hex string.");
            }
            upperInclusive = new ObjectId(upperInclusive).toHexString();
            if (lowerExclusive != null) {
                if (!ObjectId.isValid(lowerExclusive)) {
                    throw new IllegalArgumentException("Lower bound must be an ObjectId hex string.");
                }
                lowerExclusive = new ObjectId(lowerExclusive).toHexString();
            }
        }
        if (BSON_TYPE.equals(type)) {
            decode(upperInclusive);
            if (lowerExclusive != null) {
                decode(lowerExclusive);
            }
        } else if (lowerExclusive != null && compare(lowerExclusive, upperInclusive) >= 0) {
            throw new IllegalArgumentException("Lower bound must be less than upper bound.");
        }
    }

    public static MongoIdRange fromBson(BsonValue lowerExclusive, BsonValue upperInclusive) {
        return new MongoIdRange(lowerExclusive == null ? null : encode(lowerExclusive), encode(upperInclusive), BSON_TYPE);
    }

    private static String encode(BsonValue value) {
        validate(value);
        String json = new BsonDocument("value", value).toJson(JSON_SETTINGS);
        RawBsonDocument original = new RawBsonDocument(new BsonDocument("value", value), new BsonDocumentCodec());
        RawBsonDocument restored = new RawBsonDocument(new BsonDocument("value", decode(json)), new BsonDocumentCodec());
        if (!original.getByteBuffer().asNIO().equals(restored.getByteBuffer().asNIO())) {
            throw new IllegalArgumentException("MongoDB id cannot be preserved in canonical Extended JSON.");
        }
        return json;
    }

    private static BsonValue decode(String json) {
        BsonDocument document;
        try {
            document = BsonDocument.parse(json);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid canonical BSON id bound.", failure);
        }
        if (document.size() != 1 || !document.containsKey("value")) {
            throw new IllegalArgumentException("BSON id bound must contain exactly one value.");
        }
        BsonValue value = document.get("value");
        validate(value);
        return value;
    }

    static void validate(BsonValue value) {
        if (value == null || value.isArray() || value.isRegularExpression() || value.getBsonType() == BsonType.UNDEFINED) {
            throw new IllegalArgumentException("MongoDB id bound must exist and cannot be an array, regex or undefined.");
        }
    }

    static int compare(String left, String right) {
        return Arrays.compareUnsigned(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    public SourcePartition toSourcePartition() {
        Map<String, String> parameters = new HashMap<>();
        parameters.put(UPPER_INCLUSIVE, upperInclusive);
        if (lowerExclusive != null) {
            parameters.put(LOWER_EXCLUSIVE, lowerExclusive);
        }
        return new SourcePartition(type, parameters);
    }

    public static MongoIdRange fromSourcePartition(SourcePartition partition) {
        if (partition == null || (!TYPE.equals(partition.type()) && !STRING_TYPE.equals(partition.type())
                && !BSON_TYPE.equals(partition.type()))) {
            throw new IllegalArgumentException("Expected a MongoDB id range partition.");
        }
        if (!PARAMETERS.containsAll(partition.parameters().keySet())) {
            throw new IllegalArgumentException("Unknown MongoDB id range parameter.");
        }
        return new MongoIdRange(partition.parameters().get(LOWER_EXCLUSIVE),
                partition.parameters().get(UPPER_INCLUSIVE), partition.type());
    }

    public Document toFilter() {
        if (BSON_TYPE.equals(type)) {
            Document upper = new Document("$lte", List.of("$_id", new Document("$literal", decode(upperInclusive))));
            if (lowerExclusive == null) {
                return new Document("$expr", upper);
            }
            Document lower = new Document("$gt", List.of("$_id", new Document("$literal", decode(lowerExclusive))));
            return new Document("$expr", new Document("$and", List.of(lower, upper)));
        }
        Document bounds = new Document("$lte", TYPE.equals(type) ? new ObjectId(upperInclusive) : upperInclusive);
        if (lowerExclusive != null) {
            bounds.append("$gt", TYPE.equals(type) ? new ObjectId(lowerExclusive) : lowerExclusive);
        }
        return new Document("_id", bounds);
    }
}
