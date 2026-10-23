package dev.borja.nosqlbridge.runtime.source.mongodb;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.bson.Document;
import org.bson.types.ObjectId;

import dev.borja.nosqlbridge.runtime.work.SourcePartition;

/**
 * ObjectId range (lowerExclusive, upperInclusive]. A null lower bound means
 * the first range. Bounds are canonical hex strings for JSON transport.
 */
public record MongoIdRange(String lowerExclusive, String upperInclusive) {

    public static final String TYPE = "mongodb-object-id-range";
    private static final String LOWER_EXCLUSIVE = "lowerExclusive";
    private static final String UPPER_INCLUSIVE = "upperInclusive";
    private static final Set<String> PARAMETERS = Set.of(LOWER_EXCLUSIVE, UPPER_INCLUSIVE);

    public MongoIdRange {
        if (upperInclusive == null || !ObjectId.isValid(upperInclusive)) {
            throw new IllegalArgumentException("Upper bound must be an ObjectId hex string.");
        }
        upperInclusive = new ObjectId(upperInclusive).toHexString();
        if (lowerExclusive != null) {
            if (!ObjectId.isValid(lowerExclusive)) {
                throw new IllegalArgumentException("Lower bound must be an ObjectId hex string.");
            }
            lowerExclusive = new ObjectId(lowerExclusive).toHexString();
            if (lowerExclusive.compareTo(upperInclusive) >= 0) {
                throw new IllegalArgumentException("Lower bound must be less than upper bound.");
            }
        }
    }

    public SourcePartition toSourcePartition() {
        Map<String, String> parameters = new HashMap<>();
        parameters.put(UPPER_INCLUSIVE, upperInclusive);
        if (lowerExclusive != null) {
            parameters.put(LOWER_EXCLUSIVE, lowerExclusive);
        }
        return new SourcePartition(TYPE, parameters);
    }

    public static MongoIdRange fromSourcePartition(SourcePartition partition) {
        if (partition == null || !TYPE.equals(partition.type())) {
            throw new IllegalArgumentException("Expected a MongoDB ObjectId range partition.");
        }
        if (!PARAMETERS.containsAll(partition.parameters().keySet())) {
            throw new IllegalArgumentException("Unknown MongoDB ObjectId range parameter.");
        }
        return new MongoIdRange(partition.parameters().get(LOWER_EXCLUSIVE),
                partition.parameters().get(UPPER_INCLUSIVE));
    }

    public Document toFilter() {
        Document bounds = new Document("$lte", new ObjectId(upperInclusive));
        if (lowerExclusive != null) {
            bounds.append("$gt", new ObjectId(lowerExclusive));
        }
        return new Document("_id", bounds);
    }
}
