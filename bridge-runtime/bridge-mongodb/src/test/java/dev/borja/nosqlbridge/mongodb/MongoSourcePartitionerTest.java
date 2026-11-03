package dev.borja.nosqlbridge.mongodb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import com.mongodb.MongoException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import org.bson.Document;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.BsonObjectId;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.borja.nosqlbridge.common.source.partitioning.SourcePartitionCursor;
import dev.borja.nosqlbridge.common.work.SourcePartition;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MongoSourcePartitionerTest {

    @Mock
    private MongoCollection<Document> collection;
    @Mock
    private FindIterable<BsonDocument> documents;
    @Mock
    private MongoCursor<BsonDocument> cursor;

    private MongoSourcePartitioner partitioner;

    @BeforeEach
    void setUp() {
        partitioner = new MongoSourcePartitioner(collection);
    }

    @Test
    void discoversOnlyRequestedPartitionsAndContinuesFromSameCursor() {
        stubIds(id(1), id(2), id(3), id(4), id(5));

        try (SourcePartitionCursor partitions = partitioner.open(2)) {
            verifyNoInteractions(cursor);
            assertTrue(partitions.hasNext());
            assertTrue(partitions.hasNext());
            verify(cursor, never()).next();

            assertEquals(range(null, id(2)), partitions.next());
            verify(cursor, times(2)).next();
            verify(cursor, never()).close();

            assertEquals(range(id(2), id(4)), partitions.next());
            verify(cursor, times(4)).next();
            verify(cursor, never()).close();
        }

        verify(cursor, times(4)).next();
        verify(cursor).close();
        verify(collection).find(BsonDocument.class);
        verify(documents).projection(new Document("_id", 1));
        verify(documents).sort(new Document("_id", 1));
        verify(documents).hintString("_id_");
        verify(documents).iterator();
    }

    @Test
    void dividesSparseIdsIntoContiguousNonOverlappingRanges() {
        ObjectId[] ids = {id(0), id(3), id(10), id(100), id(1000)};
        stubIds((Object[]) ids);

        List<SourcePartition> partitions = readAll(2);

        assertEquals(List.of(range(null, id(3)), range(id(3), id(100)), range(id(100), id(1000))), partitions);
        for (ObjectId id : ids) {
            int matches = 0;
            for (SourcePartition partition : partitions) {
                MongoIdRange range = MongoIdRange.fromSourcePartition(partition);
                if ((range.lowerExclusive() == null || id.compareTo(BsonDocument.parse(range.lowerExclusive()).getObjectId("value").getValue()) > 0)
                        && id.compareTo(BsonDocument.parse(range.upperInclusive()).getObjectId("value").getValue()) <= 0) {
                    matches++;
                }
            }
            assertEquals(1, matches, "Each source id must belong to exactly one range.");
        }
        verify(cursor).close();
    }

    @Test
    void emptyCollectionProducesNoPartitionsAndClosesOnExhaustion() {
        stubIds();

        try (SourcePartitionCursor partitions = partitioner.open(10)) {
            assertFalse(partitions.hasNext());
            verify(cursor).close();
            assertFalse(partitions.hasNext());
            assertThrows(NoSuchElementException.class, partitions::next);
        }

        verify(cursor, never()).next();
        verify(cursor).close();
    }

    @Test
    void fewerThanPartitionSizeProducesOnePartialRange() {
        stubIds(id(1), id(2));

        assertEquals(List.of(range(null, id(2))), readAll(3));
    }

    @Test
    void exactlyPartitionSizeProducesOneRangeAndClosesBeforeReturningIt() {
        stubIds(id(1), id(2));

        try (SourcePartitionCursor partitions = partitioner.open(2)) {
            assertEquals(range(null, id(2)), partitions.next());
            verify(cursor).close();
            assertFalse(partitions.hasNext());
            assertThrows(NoSuchElementException.class, partitions::next);
        }

        verify(cursor).close();
    }

    @Test
    void oneExtraDocumentProducesSecondPartialRange() {
        stubIds(id(1), id(2), id(3));

        assertEquals(List.of(range(null, id(2)), range(id(2), id(3))), readAll(2));
    }

    @Test
    void exactMultipleDoesNotProduceTrailingEmptyPartition() {
        stubIds(id(1), id(2), id(3), id(4));

        assertEquals(List.of(range(null, id(2)), range(id(2), id(4))), readAll(2));
    }

    @Test
    void sizeOneSupportsMinimumAndMaximumObjectId() {
        ObjectId maximum = new ObjectId("ffffffffffffffffffffffff");
        stubIds(id(0), maximum);

        assertEquals(List.of(range(null, id(0)), range(id(0), maximum)), readAll(1));
    }

    @Test
    void closingBeforeAnyReadClosesMongoCursorOnce() {
        stubQuery();

        try (SourcePartitionCursor partitions = partitioner.open(10)) {
            partitions.close();
            assertThrows(IllegalStateException.class, partitions::hasNext);
            assertThrows(IllegalStateException.class, partitions::next);
        }

        verify(cursor).close();
        verify(cursor, never()).next();
        verify(cursor, never()).hasNext();
    }

    @Test
    void rejectsInvalidArgumentsWithoutQueryingCollection() {
        assertThrows(IllegalArgumentException.class, () -> new MongoSourcePartitioner(null));
        assertThrows(IllegalArgumentException.class, () -> partitioner.open(0));
        assertThrows(IllegalArgumentException.class, () -> partitioner.open(-1));
        verifyNoInteractions(collection);
    }

    @Test
    void rejectsUnsupportedIdValuesAndClosesCursor() {
        stubQuery();
        when(cursor.hasNext()).thenReturn(true);
        when(cursor.next()).thenReturn(BsonDocument.parse("{_id: []}"));

        try (SourcePartitionCursor partitions = partitioner.open(10)) {
            assertThrows(IllegalArgumentException.class, partitions::next);
            verify(cursor).close();
        }
        verify(cursor).close();
    }

    @Test
    void rejectsMissingIdAndClosesCursor() {
        stubQuery();
        when(cursor.hasNext()).thenReturn(true);
        when(cursor.next()).thenReturn(new BsonDocument());

        try (SourcePartitionCursor partitions = partitioner.open(10)) {
            assertThrows(IllegalArgumentException.class, partitions::next);
            verify(cursor).close();
        }
    }



    @Test
    void closesCursorWhenAvailabilityCheckFails() {
        stubQuery();
        MongoException failure = new MongoException("read failed");
        when(cursor.hasNext()).thenThrow(failure);

        try (SourcePartitionCursor partitions = partitioner.open(10)) {
            assertSame(failure, assertThrows(MongoException.class, partitions::hasNext));
            verify(cursor).close();
        }
    }

    @Test
    void closesCursorWhenReadingAnIdFails() {
        stubQuery();
        MongoException failure = new MongoException("read failed");
        when(cursor.hasNext()).thenReturn(true);
        when(cursor.next()).thenThrow(failure);

        try (SourcePartitionCursor partitions = partitioner.open(10)) {
            assertSame(failure, assertThrows(MongoException.class, partitions::next));
            verify(cursor).close();
        }
    }

    @Test
    void keepsReadFailureWhenCleanupAlsoFails() {
        stubQuery();
        MongoException failure = new MongoException("read failed");
        MongoException cleanupFailure = new MongoException("close failed");
        when(cursor.hasNext()).thenThrow(failure);
        doThrow(cleanupFailure).when(cursor).close();

        try (SourcePartitionCursor partitions = partitioner.open(10)) {
            assertSame(failure, assertThrows(MongoException.class, partitions::hasNext));
            assertArrayEquals(new Throwable[] {cleanupFailure}, failure.getSuppressed());
        }
        verify(cursor).close();
    }

    private List<SourcePartition> readAll(int size) {
        List<SourcePartition> result = new ArrayList<>();
        try (SourcePartitionCursor partitions = partitioner.open(size)) {
            partitions.forEachRemaining(result::add);
        }
        return result;
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 5, 10, 20})
    void stringRangesCoverEveryIdExactlyOnceWithBinaryOrdering(int size) {
        String[] ids = {"", "000000000000000000000001", "TFG-E2E-001", "TFG-E2E-002",
                "TFG-E2E-003", "a", "aa", "é", "\uE000", "\uD800\uDC00"};
        stubIds((Object[]) ids);

        List<SourcePartition> partitions = readAll(size);

        assertEquals((ids.length + size - 1) / size, partitions.size());
        String previous = null;
        for (SourcePartition partition : partitions) {
            MongoIdRange range = MongoIdRange.fromSourcePartition(partition);
            assertEquals(MongoIdRange.BSON_TYPE, range.type());
            assertEquals(previous, range.lowerExclusive());
            previous = range.upperInclusive();
        }
        for (String id : ids) {
            long matches = partitions.stream().map(MongoIdRange::fromSourcePartition)
                    .filter(range -> (range.lowerExclusive() == null || MongoIdRange.compare(id, BsonDocument.parse(range.lowerExclusive()).getString("value").getValue()) > 0)
                            && MongoIdRange.compare(id, BsonDocument.parse(range.upperInclusive()).getString("value").getValue()) <= 0).count();
            assertEquals(1, matches, id);
        }
        verify(cursor).close();
        verify(documents).collation(com.mongodb.client.model.Collation.builder().locale("simple").build());
    }

    @Test
    void discoversStringPartitionsIncrementallyAndAcceptsEmptyId() {
        stubIds("", "TFG-E2E-001", "TFG-E2E-002");

        try (SourcePartitionCursor partitions = partitioner.open(1)) {
            verifyNoInteractions(cursor);
            assertEquals(MongoIdRange.fromBson(null, new org.bson.BsonString("")).toSourcePartition(), partitions.next());
            verify(cursor, times(1)).next();
            assertEquals(MongoIdRange.fromBson(new org.bson.BsonString(""), new org.bson.BsonString("TFG-E2E-001")).toSourcePartition(), partitions.next());
            verify(cursor, times(2)).next();
        }
        verify(cursor).close();
    }





    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void keepsConfiguredPartitionSizeAcrossBsonTypes(int size) {
        stubIds(42, "TFG-E2E-001", id(1));
        List<SourcePartition> partitions = readAll(size);
        assertEquals((3 + size - 1) / size, partitions.size());
        BsonValue previous = null;
        for (int index = 0; index < partitions.size(); index++) {
            MongoIdRange range = MongoIdRange.fromSourcePartition(partitions.get(index));
            assertEquals(previous == null ? null : new BsonDocument("value", previous).toJson(
                    org.bson.json.JsonWriterSettings.builder().outputMode(org.bson.json.JsonMode.EXTENDED).build()), range.lowerExclusive());
            previous = BsonDocument.parse(range.upperInclusive()).get("value");
        }
        assertEquals(new BsonObjectId(id(1)), previous);
        verify(cursor).close();
    }

    private void stubIds(Object... ids) {
        stubQuery();
        Iterator<Object> iterator = Arrays.asList(ids).iterator();
        when(cursor.hasNext()).thenAnswer(invocation -> iterator.hasNext());
        if (ids.length > 0) {
            when(cursor.next()).thenAnswer(invocation -> new Document("_id", iterator.next()).toBsonDocument());
        }
    }

    private void stubQuery() {
        when(collection.find(BsonDocument.class)).thenReturn(documents);
        when(documents.projection(new Document("_id", 1))).thenReturn(documents);
        when(documents.sort(new Document("_id", 1))).thenReturn(documents);
        when(documents.hintString("_id_")).thenReturn(documents);
        when(documents.collation(com.mongodb.client.model.Collation.builder().locale("simple").build())).thenReturn(documents);
        when(documents.iterator()).thenReturn(cursor);
    }

    private SourcePartition range(ObjectId lower, ObjectId upper) {
        return MongoIdRange.fromBson(lower == null ? null : new BsonObjectId(lower), new BsonObjectId(upper)).toSourcePartition();
    }

    private ObjectId id(long value) {
        return new ObjectId(String.format("%024x", value));
    }
}
