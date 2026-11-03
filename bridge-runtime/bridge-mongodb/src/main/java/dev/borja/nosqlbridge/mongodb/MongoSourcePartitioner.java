package dev.borja.nosqlbridge.mongodb;

import java.util.NoSuchElementException;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Collation;
import org.bson.Document;
import org.bson.BsonDocument;
import org.bson.BsonValue;

import dev.borja.nosqlbridge.common.source.partitioning.SourcePartitionCursor;
import dev.borja.nosqlbridge.common.source.partitioning.SourcePartitioner;
import dev.borja.nosqlbridge.common.work.SourcePartition;

public class MongoSourcePartitioner implements SourcePartitioner {

    private final MongoCollection<Document> collection;

    public MongoSourcePartitioner(MongoCollection<Document> collection) {
        if (collection == null) {
            throw new IllegalArgumentException("MongoDB collection must not be null.");
        }
        this.collection = collection;
    }

    @Override
    public SourcePartitionCursor open(int recordsPerPartition) {
        if (recordsPerPartition <= 0) {
            throw new IllegalArgumentException("Records per partition must be positive.");
        }
        MongoCursor<BsonDocument> cursor = collection.find(BsonDocument.class)
                .projection(new Document("_id", 1))
                .sort(new Document("_id", 1))
                .hintString("_id_")
                .collation(Collation.builder().locale("simple").build())
                .iterator();
        return new PartitionCursor(cursor, recordsPerPartition);
    }

    private static final class PartitionCursor implements SourcePartitionCursor {

        private final MongoCursor<BsonDocument> cursor;
        private final int recordsPerPartition;
        private BsonValue upper;
        private boolean exhausted;
        private boolean closed;

        private PartitionCursor(MongoCursor<BsonDocument> cursor, int recordsPerPartition) {
            this.cursor = cursor;
            this.recordsPerPartition = recordsPerPartition;
        }

        @Override
        public boolean hasNext() {
            if (exhausted) {
                return false;
            }
            ensureOpen();
            try {
                if (cursor.hasNext()) {
                    return true;
                }
                exhausted = true;
                close();
                return false;
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(failure);
                throw failure;
            }
        }

        @Override
        public SourcePartition next() {
            if (!hasNext()) {
                throw new NoSuchElementException("No more source partitions.");
            }
            try {
                BsonValue lower = upper;
                int count = 0;
                do {
                    BsonValue id = cursor.next().get("_id");
                    MongoIdRange.validate(id);
                    upper = id;
                    count++;
                } while (count < recordsPerPartition && cursor.hasNext());
                if (!cursor.hasNext()) {
                    exhausted = true;
                    close();
                }
                return MongoIdRange.fromBson(lower, upper).toSourcePartition();
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(failure);
                throw failure;
            }
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
                throw new IllegalStateException("Source partition cursor is closed.");
            }
        }

        private void closeAfterFailure(Throwable failure) {
            try {
                close();
            } catch (RuntimeException | Error closeFailure) {
                if (closeFailure != failure) {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
    }
}
