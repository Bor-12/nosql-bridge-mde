package dev.borja.nosqlbridge.runtime.connector.mongodb;

import java.util.Map;

import org.bson.Document;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;

import dev.borja.nosqlbridge.runtime.connector.RecordCursor;
import dev.borja.nosqlbridge.runtime.connector.SourceConnector;
import dev.borja.nosqlbridge.runtime.model.LogicalRecord;
import dev.borja.nosqlbridge.runtime.plan.ConnectorPlan;

public class MongoSourceConnector implements SourceConnector {

    private final MongoClient mongoClient;
    private final MongoLogicalValueConverter converter;
    private final String databaseName;
    private final String collectionName;

    public MongoSourceConnector(MongoClient mongoClient, MongoLogicalValueConverter converter,
            ConnectorPlan plan) {
        if (mongoClient == null) {
            throw new IllegalArgumentException("MongoDB client must not be null.");
        }
        if (converter == null) {
            throw new IllegalArgumentException("MongoDB logical value converter must not be null.");
        }
        if (plan == null) {
            throw new IllegalArgumentException("MongoDB connector plan must not be null.");
        }
        if (!"mongodb".equals(plan.technology())) {
            throw new IllegalArgumentException("MongoDB connector technology must be mongodb.");
        }
        this.databaseName = requiredConfig(plan.config(), "database");
        this.collectionName = requiredConfig(plan.config(), "collection");
        this.mongoClient = mongoClient;
        this.converter = converter;
    }

    @Override
    public RecordCursor open() {
        MongoDatabase database = mongoClient.getDatabase(databaseName);
        MongoCollection<Document> collection = database.getCollection(collectionName);
        MongoCursor<Document> cursor = collection.find().iterator();
        return new MongoRecordCursor(cursor);
    }

    private String requiredConfig(Map<String, String> config, String name) {
        String value = config == null ? null : config.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("MongoDB " + name + " must not be blank.");
        }
        return value;
    }

    private final class MongoRecordCursor implements RecordCursor {

        private final MongoCursor<Document> cursor;
        private boolean closed;

        private MongoRecordCursor(MongoCursor<Document> cursor) {
            this.cursor = cursor;
        }

        @Override
        public boolean hasNext() {
            ensureOpen();
            return cursor.hasNext();
        }

        @Override
        public LogicalRecord next() {
            ensureOpen();
            return converter.toLogicalRecord(cursor.next());
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                cursor.close();
            }
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("MongoDB record cursor is closed.");
            }
        }
    }
}
