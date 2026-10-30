package dev.borja.nosqlbridge.mongodb;

import java.util.NoSuchElementException;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import org.bson.Document;
import org.bson.types.ObjectId;

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
        MongoCursor<Document> cursor = collection.find()
                .projection(new Document("_id", 1))
                .sort(new Document("_id", 1))
                .hintString("_id_")
                .iterator();
        return new PartitionCursor(cursor, recordsPerPartition);
    }

    private static final class PartitionCursor implements SourcePartitionCursor {

        private final MongoCursor<Document> cursor;
        private final int recordsPerPartition;
        private String upper;
        private boolean exhausted;
        private boolean closed;

        private PartitionCursor(MongoCursor<Document> cursor, int recordsPerPartition) {
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
                String lower = upper;
                int count = 0;
                do {
                    Object id = cursor.next().get("_id");
                    if (!(id instanceof ObjectId objectId)) {
                        throw new IllegalArgumentException("MongoDB partitioning requires ObjectId _id values.");
                    }
                    String next = objectId.toHexString();
                    if (upper != null && next.compareTo(upper) <= 0) {
                        throw new IllegalArgumentException("MongoDB partitioning requires strictly increasing unique ids.");
                    }
                    upper = next;
                    count++;
                } while (count < recordsPerPartition && cursor.hasNext());
                if (!cursor.hasNext()) {
                    exhausted = true;
                    close();
                }
                return new MongoIdRange(lower, upper).toSourcePartition();
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
