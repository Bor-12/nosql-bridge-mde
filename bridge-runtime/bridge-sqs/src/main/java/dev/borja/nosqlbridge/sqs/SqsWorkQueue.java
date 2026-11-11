package dev.borja.nosqlbridge.sqs;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import dev.borja.nosqlbridge.common.queue.WorkQueue;
import dev.borja.nosqlbridge.common.work.WorkUnitMessage;
import dev.borja.nosqlbridge.common.work.WorkUnitMessageSerializer;

public class SqsWorkQueue implements WorkQueue {

    private static final int MAX_MESSAGE_BYTES = 1_048_576;
    private static final int DEFAULT_MAX_MESSAGE_BYTES = 262_144;

    private final SqsClient client;
    private final WorkUnitMessageSerializer serializer;
    private final String queueUrl;
    private int cachedMaximumMessageSize;
    private final int visibilityTimeoutSeconds;

    public SqsWorkQueue(SqsClient client, WorkUnitMessageSerializer serializer, String queueUrl) {
        this(client, serializer, queueUrl, 120);
    }

    public SqsWorkQueue(SqsClient client, WorkUnitMessageSerializer serializer, String queueUrl, int visibilityTimeoutSeconds) {
        if (visibilityTimeoutSeconds < 30 || visibilityTimeoutSeconds > 43200) {
            throw new IllegalArgumentException("SQS visibility timeout must be between 30 and 43200 seconds.");
        }
        this.visibilityTimeoutSeconds = visibilityTimeoutSeconds;
        if (client == null) {
            throw new IllegalArgumentException("SQS client must not be null.");
        }
        if (serializer == null) {
            throw new IllegalArgumentException("Work unit message serializer must not be null.");
        }
        if (queueUrl == null || queueUrl.isBlank()) {
            throw new IllegalArgumentException("SQS queue URL must not be blank.");
        }
        URI address = URI.create(queueUrl);
        if (address.getHost() == null || !("https".equalsIgnoreCase(address.getScheme())
                || "http".equalsIgnoreCase(address.getScheme()))) {
            throw new IllegalArgumentException("SQS queue URL must be an absolute HTTP(S) URL.");
        }
        if (address.getPath().endsWith(".fifo")) {
            throw new IllegalArgumentException("Only standard SQS work queues are supported.");
        }
        this.client = client;
        this.serializer = serializer;
        this.queueUrl = queueUrl;
    }

    @Override
    public void publish(WorkUnitMessage message) {
        String json = serializer.serialize(message);
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        checkSize(bytes, MAX_MESSAGE_BYTES);
        checkSize(bytes, maximumMessageSize());
        client.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(json).build());
    }

    @Override
    public Optional<Delivery> receive() {
        List<Message> messages = client.receiveMessage(ReceiveMessageRequest.builder().queueUrl(queueUrl)
                .maxNumberOfMessages(1).waitTimeSeconds(20).visibilityTimeout(visibilityTimeoutSeconds).build()).messages();
        if (messages.isEmpty()) {
            return Optional.empty();
        }
        Message received = messages.getFirst();
        WorkUnitMessage message = serializer.deserialize(received.body());
        return Optional.of(new Delivery(message, received.receiptHandle()));
    }

    @Override
    public Lease maintainVisibility(Delivery delivery) {
        if (delivery == null) throw new IllegalArgumentException("Delivery must not be null.");
        var scheduler = visibilityScheduler();
        class VisibilityLease implements Lease {
            private RuntimeException failure;
            private boolean closed;

            synchronized void renew() {
                if (closed || failure != null) return;
                try {
                    client.changeMessageVisibility(ChangeMessageVisibilityRequest.builder().queueUrl(queueUrl)
                            .receiptHandle(delivery.acknowledgementToken()).visibilityTimeout(visibilityTimeoutSeconds).build());
                } catch (RuntimeException error) { failure = error; }
            }
            public synchronized void check() {
                if (failure != null) throw new IllegalStateException("Could not renew SQS visibility; delivery will not be acknowledged.", failure);
            }
            public void close() {
                // Wait for an in-flight renewal before freezing its result. Do not
                // interrupt a successful request and mistake cancellation for failure.
                synchronized (this) { closed = true; }
                scheduler.shutdownNow();
            }
        }
        var lease = new VisibilityLease();
        try {
            lease.renew();
            scheduler.scheduleWithFixedDelay(lease::renew, visibilityTimeoutSeconds / 3, visibilityTimeoutSeconds / 3, TimeUnit.SECONDS);
            return lease;
        } catch (RuntimeException | Error failure) {
            lease.close();
            throw failure;
        }
    }

    ScheduledExecutorService visibilityScheduler() {
        return Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "bridge-sqs-visibility");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void acknowledge(Delivery delivery) {
        if (delivery == null) {
            throw new IllegalArgumentException("Delivery must not be null.");
        }
        client.deleteMessage(DeleteMessageRequest.builder().queueUrl(queueUrl)
                .receiptHandle(delivery.acknowledgementToken()).build());
    }

    private synchronized int maximumMessageSize() {
        if (cachedMaximumMessageSize > 0) {
            return cachedMaximumMessageSize;
        }
        String configured = client.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.MAXIMUM_MESSAGE_SIZE).build())
                .attributes().get(QueueAttributeName.MAXIMUM_MESSAGE_SIZE);
        if (configured == null) {
            cachedMaximumMessageSize = DEFAULT_MAX_MESSAGE_BYTES;
            return cachedMaximumMessageSize;
        }
        int limit;
        try {
            limit = Integer.parseInt(configured);
        } catch (NumberFormatException failure) {
            throw new IllegalStateException("SQS queue returned an invalid MaximumMessageSize.", failure);
        }
        if (limit <= 0) {
            throw new IllegalStateException("SQS queue returned an invalid MaximumMessageSize.");
        }
        cachedMaximumMessageSize = Math.min(limit, MAX_MESSAGE_BYTES);
        return cachedMaximumMessageSize;
    }

    private void checkSize(int bytes, int limit) {
        if (bytes > limit) {
            throw new IllegalArgumentException("Work unit message is " + bytes
                    + " UTF-8 bytes; SQS queue limit is " + limit + ".");
        }
    }
}
