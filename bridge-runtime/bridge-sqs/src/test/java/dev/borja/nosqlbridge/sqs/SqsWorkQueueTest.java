package dev.borja.nosqlbridge.sqs;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import dev.borja.nosqlbridge.common.plan.BridgeExecutionPlan;
import dev.borja.nosqlbridge.common.plan.ConnectorPlan;
import dev.borja.nosqlbridge.common.queue.WorkQueue.Delivery;
import dev.borja.nosqlbridge.common.work.MigrationId;
import dev.borja.nosqlbridge.common.work.SourcePartition;
import dev.borja.nosqlbridge.common.work.WorkUnit;
import dev.borja.nosqlbridge.common.work.WorkUnitId;
import dev.borja.nosqlbridge.common.work.WorkUnitMessage;
import dev.borja.nosqlbridge.common.work.WorkUnitMessageSerializer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SqsWorkQueueTest {

    private static final String QUEUE_URL = "https://sqs.eu-west-1.amazonaws.com/123456789012/work";

    @Mock
    private SqsClient client;

    private final ObjectMapper mapper = new ObjectMapper();
    private final WorkUnitMessageSerializer serializer = new WorkUnitMessageSerializer(mapper);
    private final WorkUnit unit = new WorkUnit(
            new MigrationId(UUID.fromString("550e8400-e29b-41d4-a716-446655440000")), new WorkUnitId(0),
            new SourcePartition("test-range", Map.of("upper", "10")));

    @Test
    void publishesSerializedMessagesAfterReadingTheQueueLimitOnce() {
        WorkUnitMessage message = messageWithName("test");
        stubLimit("1048576");
        SqsWorkQueue queue = queue();
        verifyNoInteractions(client);

        queue.publish(message);
        queue.publish(message);

        verify(client).getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(QUEUE_URL)
                .attributeNames(QueueAttributeName.MAXIMUM_MESSAGE_SIZE).build());
        verify(client, times(2)).sendMessage(SendMessageRequest.builder().queueUrl(QUEUE_URL)
                .messageBody(serializer.serialize(message)).build());
        verifyNoMoreInteractions(client);
    }

    @Test
    void retriesQueueLimitLookupAfterSdkFailure() {
        SqsException failure = QueueDoesNotExistException.builder().message("Queue unavailable").build();
        when(client.getQueueAttributes(any(GetQueueAttributesRequest.class))).thenThrow(failure)
                .thenReturn(GetQueueAttributesResponse.builder()
                        .attributes(Map.of(QueueAttributeName.MAXIMUM_MESSAGE_SIZE, "1048576")).build());
        SqsWorkQueue queue = queue();
        WorkUnitMessage message = messageWithName("test");

        assertSame(failure, assertThrows(SqsException.class, () -> queue.publish(message)));
        queue.publish(message);
        queue.publish(message);

        verify(client, times(2)).getQueueAttributes(any(GetQueueAttributesRequest.class));
        verify(client, times(2)).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void initializesQueueLimitOnceForConcurrentPublications() throws Exception {
        CountDownLatch lookupStarted = new CountDownLatch(1);
        CountDownLatch releaseLookup = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        when(client.getQueueAttributes(any(GetQueueAttributesRequest.class))).thenAnswer(invocation -> {
            lookupStarted.countDown();
            assertTrue(releaseLookup.await(5, TimeUnit.SECONDS));
            return GetQueueAttributesResponse.builder()
                    .attributes(Map.of(QueueAttributeName.MAXIMUM_MESSAGE_SIZE, "1048576")).build();
        });
        SqsWorkQueue queue = queue();
        WorkUnitMessage message = messageWithName("test");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> queue.publish(message));
            assertTrue(lookupStarted.await(5, TimeUnit.SECONDS));
            Future<?> second = executor.submit(() -> {
                secondStarted.countDown();
                queue.publish(message);
            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            releaseLookup.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            releaseLookup.countDown();
            executor.shutdownNow();
        }

        verify(client).getQueueAttributes(any(GetQueueAttributesRequest.class));
        verify(client, times(2)).sendMessage(any(SendMessageRequest.class));
        verifyNoMoreInteractions(client);
    }

    @Test
    void receivesOneMessageWithLongPollingAndDeletesOnlyOnExplicitAcknowledgement() {
        WorkUnitMessage message = messageWithName("test");
        when(client.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(Message.builder().messageId("message-id")
                        .receiptHandle("current-receipt").body(serializer.serialize(message)).build()).build());
        SqsWorkQueue queue = queue();

        Delivery delivery = queue.receive().orElseThrow();

        assertEquals(message, delivery.message());
        assertEquals("current-receipt", delivery.acknowledgementToken());
        verify(client).receiveMessage(ReceiveMessageRequest.builder().queueUrl(QUEUE_URL)
                .maxNumberOfMessages(1).waitTimeSeconds(20).build());
        verify(client, never()).deleteMessage(any(DeleteMessageRequest.class));

        queue.acknowledge(delivery);

        verify(client).deleteMessage(DeleteMessageRequest.builder().queueUrl(QUEUE_URL)
                .receiptHandle("current-receipt").build());
        verifyNoMoreInteractions(client);
    }

    @Test
    void returnsEmptyAfterPollingWithoutMessages() {
        when(client.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder().build());

        assertTrue(queue().receive().isEmpty());

        verify(client).receiveMessage(any(ReceiveMessageRequest.class));
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{broken", "{}", "[]", "", " "})
    void propagatesInvalidJsonWithoutDeletingTheMessage(String body) {
        stubReceived(body, "receipt");

        assertThrows(JacksonException.class, () -> queue().receive());

        verify(client, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void rejectsMissingBodyOrReceiptWithoutDeletingTheMessage() {
        stubReceived(null, "receipt");
        assertThrows(IllegalArgumentException.class, () -> queue().receive());
        stubReceived(serializer.serialize(messageWithName("test")), null);
        assertThrows(IllegalArgumentException.class, () -> queue().receive());
        verify(client, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @ParameterizedTest
    @CsvSource({"65535, 65536, true", "65536, 65536, true", "65537, 65536, false",
            "70000, 1048576, true", "1048576, 1048576, true", "1048577, 1048576, false",
            "1048576, 2097152, true"})
    void enforcesActualQueueAndServiceLimits(int bytes, int queueLimit, boolean accepted) {
        WorkUnitMessage message = messageWithSize(bytes);
        if (bytes <= 1_048_576) {
            stubLimit(Integer.toString(queueLimit));
        }

        if (accepted) {
            queue().publish(message);
            verify(client).sendMessage(any(SendMessageRequest.class));
        } else {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> queue().publish(message));
            assertEquals("Work unit message is " + bytes + " UTF-8 bytes; SQS queue limit is " + queueLimit + ".",
                    failure.getMessage());
            verify(client, never()).sendMessage(any(SendMessageRequest.class));
            if (bytes > 1_048_576) {
                verifyNoInteractions(client);
            }
        }
    }

    @Test
    void measuresUtf8BytesInsteadOfCharacters() {
        WorkUnitMessage message = messageWithName("é".repeat(40_000));
        String json = serializer.serialize(message);
        assertTrue(json.length() < 65_536);
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length > 65_536);
        stubLimit("65536");

        assertThrows(IllegalArgumentException.class, () -> queue().publish(message));

        verify(client, never()).sendMessage(any(SendMessageRequest.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "0", "-1"})
    void refusesPublishingWhenQueueLimitIsInvalid(String limit) {
        stubLimit(limit);
        SqsWorkQueue queue = queue();

        assertThrows(IllegalStateException.class, () -> queue.publish(messageWithName("test")));

        verify(client, never()).sendMessage(any(SendMessageRequest.class));
        stubLimit("1048576");
        queue.publish(messageWithName("test"));
        verify(client, times(2)).getQueueAttributes(any(GetQueueAttributesRequest.class));
        verify(client).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void refusesPublishingWhenQueueLimitIsMissing() {
        when(client.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                .thenReturn(GetQueueAttributesResponse.builder().build());

        assertThrows(IllegalStateException.class, () -> queue().publish(messageWithName("test")));

        verify(client, never()).sendMessage(any(SendMessageRequest.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"publish", "receive", "acknowledge", "attributes"})
    void propagatesSdkFailuresWithoutOwnRetriesOrAutomaticDeletion(String operation) {
        SqsException failure = QueueDoesNotExistException.builder().message("Queue unavailable").build();
        SqsWorkQueue queue = queue();
        WorkUnitMessage message = messageWithName("test");
        switch (operation) {
            case "publish" -> {
                stubLimit("1048576");
                when(client.sendMessage(any(SendMessageRequest.class))).thenThrow(failure);
                assertSame(failure, assertThrows(SqsException.class, () -> queue.publish(message)));
                verify(client).sendMessage(any(SendMessageRequest.class));
            }
            case "receive" -> {
                when(client.receiveMessage(any(ReceiveMessageRequest.class))).thenThrow(failure);
                assertSame(failure, assertThrows(SqsException.class, queue::receive));
                verify(client).receiveMessage(any(ReceiveMessageRequest.class));
            }
            case "acknowledge" -> {
                when(client.deleteMessage(any(DeleteMessageRequest.class))).thenThrow(failure);
                assertSame(failure, assertThrows(SqsException.class,
                        () -> queue.acknowledge(new Delivery(message, "receipt"))));
                verify(client).deleteMessage(any(DeleteMessageRequest.class));
            }
            case "attributes" -> {
                when(client.getQueueAttributes(any(GetQueueAttributesRequest.class))).thenThrow(failure);
                assertSame(failure, assertThrows(SqsException.class, () -> queue.publish(message)));
                verify(client).getQueueAttributes(any(GetQueueAttributesRequest.class));
            }
            default -> throw new AssertionError(operation);
        }
        if (!"acknowledge".equals(operation)) {
            verify(client, never()).deleteMessage(any(DeleteMessageRequest.class));
        }
    }

    @Test
    void rejectsNullMessagesDeliveriesAndInvalidTokensBeforeCallingSqs() {
        assertThrows(IllegalArgumentException.class, () -> queue().publish(null));
        assertThrows(IllegalArgumentException.class, () -> queue().acknowledge(null));
        assertThrows(IllegalArgumentException.class, () -> new Delivery(null, "receipt"));
        assertThrows(IllegalArgumentException.class, () -> new Delivery(messageWithName("test"), null));
        assertThrows(IllegalArgumentException.class, () -> new Delivery(messageWithName("test"), " "));
        verifyNoInteractions(client);
    }

    @Test
    void rejectsMissingDependenciesAndQueueUrl() {
        assertThrows(IllegalArgumentException.class, () -> new SqsWorkQueue(null, serializer, QUEUE_URL));
        assertThrows(IllegalArgumentException.class, () -> new SqsWorkQueue(client, null, QUEUE_URL));
        assertThrows(IllegalArgumentException.class, () -> new SqsWorkQueue(client, serializer, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "relative/path", "ftp://localhost/queue", "https:///queue",
            "https://sqs.eu-west-1.amazonaws.com/123456789012/work.fifo"})
    void rejectsInvalidOrUnsupportedQueueUrls(String url) {
        assertThrows(IllegalArgumentException.class, () -> new SqsWorkQueue(client, serializer, url));
        verifyNoInteractions(client);
    }

    private SqsWorkQueue queue() {
        return new SqsWorkQueue(client, serializer, QUEUE_URL);
    }

    private WorkUnitMessage messageWithSize(int bytes) {
        int overhead = mapper.writeValueAsBytes(messageWithName("")).length;
        return messageWithName("x".repeat(bytes - overhead));
    }

    private WorkUnitMessage messageWithName(String name) {
        return new WorkUnitMessage(unit, new BridgeExecutionPlan(1, name,
                new ConnectorPlan("source", Map.of()), new ConnectorPlan("target", Map.of()),
                "drop", "omit", List.of()));
    }

    private void stubLimit(String bytes) {
        when(client.getQueueAttributes(any(GetQueueAttributesRequest.class))).thenReturn(GetQueueAttributesResponse.builder()
                .attributes(Map.of(QueueAttributeName.MAXIMUM_MESSAGE_SIZE, bytes)).build());
    }

    private void stubReceived(String body, String receipt) {
        when(client.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder()
                .messages(Message.builder().body(body).receiptHandle(receipt).build()).build());
    }
}
