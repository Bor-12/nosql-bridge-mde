package dev.borja.nosqlbridge.sqs;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

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

    private final SqsClient client;
    private final WorkUnitMessageSerializer serializer;
    private final String queueUrl;
    private int cachedMaximumMessageSize;

    public SqsWorkQueue(SqsClient client, WorkUnitMessageSerializer serializer, String queueUrl) {
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
                .maxNumberOfMessages(1).waitTimeSeconds(20).build()).messages();
        if (messages.isEmpty()) {
            return Optional.empty();
        }
        Message received = messages.getFirst();
        WorkUnitMessage message = serializer.deserialize(received.body());
        return Optional.of(new Delivery(message, received.receiptHandle()));
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
