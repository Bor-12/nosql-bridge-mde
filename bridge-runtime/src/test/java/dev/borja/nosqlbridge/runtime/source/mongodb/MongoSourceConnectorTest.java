package dev.borja.nosqlbridge.runtime.source.mongodb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import com.mongodb.MongoException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.borja.nosqlbridge.runtime.model.LogicalRecord;
import dev.borja.nosqlbridge.runtime.model.LogicalValue;
import dev.borja.nosqlbridge.runtime.plan.ConnectorPlan;
import dev.borja.nosqlbridge.runtime.source.connector.RecordCursor;
import dev.borja.nosqlbridge.runtime.source.connector.SourceConnector;
import dev.borja.nosqlbridge.runtime.work.SourcePartition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MongoSourceConnectorTest {

    @Mock
    private MongoClient mongoClient;
    @Mock
    private MongoDatabase database;
    @Mock
    private MongoCollection<Document> collection;
    @Mock
    private FindIterable<Document> documents;
    @Mock
    private MongoCursor<Document> mongoCursor;

    private final MongoLogicalValueConverter converter = new MongoLogicalValueConverter();
    private SourceConnector connector;

    @BeforeEach
    void setUp() {
        connector = new MongoSourceConnector(mongoClient, converter,
                new ConnectorPlan("mongodb", Map.of("database", "shop", "collection", "products")));
    }

    @Test
    void opensConfiguredCollectionWithoutReadingDocuments() {
        stubOpen();
        verifyNoInteractions(mongoClient);

        try (RecordCursor cursor = connector.open()) {
            verify(mongoClient).getDatabase("shop");
            verify(database).getCollection("products");
            verify(collection).find();
            verify(documents).iterator();
            verifyNoInteractions(mongoCursor);
        }

        verify(mongoCursor).close();
        verify(mongoClient, never()).close();
    }

    @Test
    void opensFirstPartitionWithInclusiveUpperBound() {
        String upper = "000000000000000000000001";
        Document filter = new Document("_id", new Document("$lte", new ObjectId(upper)));
        when(mongoClient.getDatabase("shop")).thenReturn(database);
        when(database.getCollection("products")).thenReturn(collection);
        when(collection.find(filter)).thenReturn(documents);
        when(documents.hintString("_id_")).thenReturn(documents);
        when(documents.iterator()).thenReturn(mongoCursor);
        when(mongoCursor.next()).thenReturn(new Document("_id", new ObjectId(upper)).append("name", "Product"));

        try (RecordCursor cursor = connector.open(new MongoIdRange(null, upper).toSourcePartition())) {
            assertEquals(new LogicalRecord(Map.of(
                    "_id", new LogicalValue.StringValue(upper),
                    "name", new LogicalValue.StringValue("Product"))), cursor.next());
        }

        verify(collection).find(filter);
        verify(collection, never()).find();
        verify(mongoCursor).close();
    }

    @Test
    void reopensBoundedPartitionUsingTheSameLimits() {
        String lower = "000000000000000000000001";
        String upper = "000000000000000000000002";
        SourcePartition partition = new MongoIdRange(lower, upper).toSourcePartition();
        Document filter = new Document("_id", new Document("$gt", new ObjectId(lower))
                .append("$lte", new ObjectId(upper)));
        when(mongoClient.getDatabase("shop")).thenReturn(database);
        when(database.getCollection("products")).thenReturn(collection);
        when(collection.find(filter)).thenReturn(documents);
        when(documents.hintString("_id_")).thenReturn(documents);
        when(documents.iterator()).thenReturn(mongoCursor);
        when(mongoCursor.hasNext()).thenReturn(false);

        for (int attempt = 0; attempt < 2; attempt++) {
            try (RecordCursor cursor = connector.open(partition)) {
                assertFalse(cursor.hasNext());
            }
        }

        verify(collection, times(2)).find(filter);
        verify(documents, times(2)).iterator();
        verify(mongoCursor, times(2)).close();
        verify(collection, never()).find();
    }

    @Test
    void rejectsInvalidPartitionBeforeAccessingMongo() {
        assertThrows(IllegalArgumentException.class, () -> connector.open(null));
        assertThrows(IllegalArgumentException.class,
                () -> connector.open(new SourcePartition("other-source", Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> connector.open(new SourcePartition(MongoIdRange.TYPE, Map.of())));
        verifyNoInteractions(mongoClient);
    }

    @Test
    void readsAndConvertsOneDocumentUsingForEachRemaining() {
        stubOpen();
        when(mongoCursor.hasNext()).thenReturn(true, false);
        when(mongoCursor.next()).thenReturn(new Document("_id", "P001").append("name", "Product A"));

        List<LogicalRecord> records = new ArrayList<>();
        try (RecordCursor cursor = connector.open()) {
            cursor.forEachRemaining(records::add);
            assertEquals(List.of(new LogicalRecord(Map.of(
                    "_id", new LogicalValue.StringValue("P001"),
                    "name", new LogicalValue.StringValue("Product A")))), records);
            assertFalse(cursor.hasNext());
        }
        verify(mongoCursor).next();
        verify(mongoCursor).close();
    }

    @Test
    void readsMultipleDocumentsIncrementally() {
        stubOpen();
        when(mongoCursor.hasNext()).thenReturn(true, true, true, false);
        when(mongoCursor.next()).thenReturn(new Document("_id", "P001"),
                new Document("_id", "P002"), new Document("_id", "P003"));

        try (RecordCursor cursor = connector.open()) {
            assertTrue(cursor.hasNext());
            verify(mongoCursor, never()).next();
            assertEquals(new LogicalValue.StringValue("P001"), cursor.next().get("_id"));
            verify(mongoCursor, times(1)).next();
            assertTrue(cursor.hasNext());
            assertEquals(new LogicalValue.StringValue("P002"), cursor.next().get("_id"));
            verify(mongoCursor, times(2)).next();
            assertTrue(cursor.hasNext());
            assertEquals(new LogicalValue.StringValue("P003"), cursor.next().get("_id"));
            verify(mongoCursor, times(3)).next();
            assertFalse(cursor.hasNext());
        }
    }

    @Test
    void handlesEmptyCursor() {
        stubOpen();
        when(mongoCursor.hasNext()).thenReturn(false);

        try (RecordCursor cursor = connector.open()) {
            assertFalse(cursor.hasNext());
            verify(mongoCursor, never()).next();
        }
        verify(mongoCursor).close();
    }

    @Test
    void convertsNestedDocumentThroughRealConverter() {
        stubOpen();
        when(mongoCursor.next()).thenReturn(new Document("category", new Document("name", "Informatica")));

        try (RecordCursor cursor = connector.open()) {
            assertEquals(new LogicalValue.ObjectValue(Map.of("name", new LogicalValue.StringValue("Informatica"))),
                    cursor.next().get("category"));
        }
    }

    @Test
    void convertsNullFieldThroughRealConverter() {
        stubOpen();
        when(mongoCursor.next()).thenReturn(new Document("description", null));

        try (RecordCursor cursor = connector.open()) {
            LogicalRecord record = cursor.next();
            assertTrue(record.contains("description"));
            assertEquals(new LogicalValue.NullValue(), record.get("description"));
            assertFalse(record.contains("missing"));
        }
    }

    @Test
    void closesUnderlyingCursorOnceEvenWhenClosedRepeatedly() {
        stubOpen();

        try (RecordCursor cursor = connector.open()) {
            cursor.close();
            cursor.close();
        }

        verify(mongoCursor, times(1)).close();
        verify(mongoClient, never()).close();
    }

    @Test
    void closesCursorWhenCallerStopsEarly() {
        stubOpen();
        when(mongoCursor.next()).thenReturn(new Document("_id", "P001"));

        try (RecordCursor cursor = connector.open()) {
            cursor.next();
        }

        verify(mongoCursor).close();
        verify(mongoCursor, times(1)).next();
    }

    @Test
    void rejectsReadingClosedCursor() {
        stubOpen();

        try (RecordCursor cursor = connector.open()) {
            cursor.close();
            assertEquals("MongoDB record cursor is closed.",
                    assertThrows(IllegalStateException.class, cursor::hasNext).getMessage());
            assertEquals("MongoDB record cursor is closed.",
                    assertThrows(IllegalStateException.class, cursor::next).getMessage());
        }

        verify(mongoCursor, never()).hasNext();
        verify(mongoCursor, never()).next();
    }

    @Test
    void rejectsMissingDatabase() {
        assertInvalidConfig(Map.of("collection", "products"), "database");
    }

    @Test
    void rejectsNullDatabase() {
        Map<String, String> config = new HashMap<>();
        config.put("database", null);
        config.put("collection", "products");
        assertInvalidConfig(config, "database");
    }

    @Test
    void rejectsBlankDatabase() {
        assertInvalidConfig(Map.of("database", " \t", "collection", "products"), "database");
    }

    @Test
    void rejectsMissingCollection() {
        assertInvalidConfig(Map.of("database", "shop"), "collection");
    }

    @Test
    void rejectsNullCollection() {
        Map<String, String> config = new HashMap<>();
        config.put("database", "shop");
        config.put("collection", null);
        assertInvalidConfig(config, "collection");
    }

    @Test
    void rejectsBlankCollection() {
        assertInvalidConfig(Map.of("database", "shop", "collection", " \t"), "collection");
    }

    @Test
    void rejectsNullConfig() {
        assertInvalidConfig(null, "database");
    }

    @Test
    void rejectsWrongOrNullTechnology() {
        Map<String, String> config = Map.of("database", "shop", "collection", "products");
        IllegalArgumentException wrong = assertThrows(IllegalArgumentException.class,
                () -> new MongoSourceConnector(mongoClient, converter, new ConnectorPlan("dynamodb", config)));
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> new MongoSourceConnector(mongoClient, converter, new ConnectorPlan(null, config)));
        assertEquals("MongoDB connector technology must be mongodb.", wrong.getMessage());
        assertEquals(wrong.getMessage(), missing.getMessage());
        verifyNoInteractions(mongoClient);
    }

    @Test
    void rejectsNullPlan() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new MongoSourceConnector(mongoClient, converter, null));
        assertEquals("MongoDB connector plan must not be null.", exception.getMessage());
    }

    @Test
    void rejectsNullDependencies() {
        ConnectorPlan plan = new ConnectorPlan("mongodb", Map.of("database", "shop", "collection", "products"));
        assertEquals("MongoDB client must not be null.", assertThrows(IllegalArgumentException.class,
                () -> new MongoSourceConnector(null, converter, plan)).getMessage());
        assertEquals("MongoDB logical value converter must not be null.", assertThrows(IllegalArgumentException.class,
                () -> new MongoSourceConnector(mongoClient, null, plan)).getMessage());
    }

    @Test
    void capturesConfiguredNamesAtConstruction() {
        Map<String, String> config = new HashMap<>(Map.of("database", "shop", "collection", "products"));
        SourceConnector source = new MongoSourceConnector(mongoClient, converter, new ConnectorPlan("mongodb", config));
        config.put("database", "other");
        config.put("collection", "other");
        stubOpen();

        try (RecordCursor cursor = source.open()) {
            verify(mongoClient).getDatabase("shop");
            verify(database).getCollection("products");
        }
    }

    @Test
    void propagatesDatabaseAccessException() {
        MongoException failure = new MongoException("database unavailable");
        when(mongoClient.getDatabase("shop")).thenThrow(failure);
        assertSame(failure, assertThrows(MongoException.class, connector::open));
    }

    @Test
    void propagatesFindException() {
        MongoException failure = new MongoException("find failed");
        when(mongoClient.getDatabase("shop")).thenReturn(database);
        when(database.getCollection("products")).thenReturn(collection);
        when(collection.find()).thenThrow(failure);
        assertSame(failure, assertThrows(MongoException.class, connector::open));
    }

    @Test
    void propagatesIterationExceptionAndClosesCursor() {
        stubOpen();
        MongoException failure = new MongoException("iteration failed");
        when(mongoCursor.hasNext()).thenThrow(failure);

        MongoException result = assertThrows(MongoException.class, () -> {
            try (RecordCursor cursor = connector.open()) {
                cursor.hasNext();
            }
        });

        assertSame(failure, result);
        verify(mongoCursor).close();
    }

    @Test
    void propagatesNextExceptionAndClosesCursor() {
        stubOpen();
        MongoException failure = new MongoException("read failed");
        when(mongoCursor.next()).thenThrow(failure);

        MongoException result = assertThrows(MongoException.class, () -> {
            try (RecordCursor cursor = connector.open()) {
                cursor.next();
            }
        });

        assertSame(failure, result);
        verify(mongoCursor).close();
    }

    @Test
    void propagatesExhaustedCursorException() {
        stubOpen();
        NoSuchElementException failure = new NoSuchElementException("cursor exhausted");
        when(mongoCursor.next()).thenThrow(failure);

        try (RecordCursor cursor = connector.open()) {
            assertSame(failure, assertThrows(NoSuchElementException.class, cursor::next));
        }
    }

    @Test
    void propagatesConversionExceptionAndClosesCursor() {
        stubOpen();
        when(mongoCursor.next()).thenReturn(new Document("unsupported", new Object()));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            try (RecordCursor cursor = connector.open()) {
                cursor.next();
            }
        });

        assertEquals("Unsupported MongoDB value type: java.lang.Object", exception.getMessage());
        verify(mongoCursor, times(1)).next();
        verify(mongoCursor).close();
    }

    private void stubOpen() {
        when(mongoClient.getDatabase("shop")).thenReturn(database);
        when(database.getCollection("products")).thenReturn(collection);
        when(collection.find()).thenReturn(documents);
        when(documents.iterator()).thenReturn(mongoCursor);
    }

    private void assertInvalidConfig(Map<String, String> config, String field) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new MongoSourceConnector(mongoClient, converter, new ConnectorPlan("mongodb", config)));
        assertEquals("MongoDB " + field + " must not be blank.", exception.getMessage());
        verifyNoInteractions(mongoClient);
    }
}
