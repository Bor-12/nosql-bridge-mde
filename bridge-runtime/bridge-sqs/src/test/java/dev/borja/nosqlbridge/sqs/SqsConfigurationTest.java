package dev.borja.nosqlbridge.sqs;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

import dev.borja.nosqlbridge.common.config.CommonConfiguration;
import dev.borja.nosqlbridge.common.queue.WorkQueue;
import org.springframework.boot.CommandLineRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SqsConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CommonConfiguration.class, SqsConfiguration.class);
    private final String queueProperty = "bridge.sqs.queue-url=https://sqs.eu-west-1.amazonaws.com/123456789012/work";

    @Test
    void doesNotRegisterSqsComponentsWithoutQueueUrl() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(SqsClient.class).isEmpty());
            assertTrue(context.getBeansOfType(WorkQueue.class).isEmpty());
        });
    }

    @Test
    void reusesInjectedClientWithoutNetworkCallsOrLocalPlan() {
        SqsClient client = mock(SqsClient.class);
        contextRunner.withBean(SqsClient.class, () -> client).withPropertyValues(queueProperty).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(SqsClient.class).size());
            assertEquals(1, context.getBeansOfType(WorkQueue.class).size());
            assertSame(client, context.getBean(SqsClient.class));
            assertTrue(context.getBean(WorkQueue.class) instanceof SqsWorkQueue);
            assertTrue(context.getBeansOfType(CommandLineRunner.class).isEmpty());
            verifyNoInteractions(client);
        });
    }

    @Test
    void doesNotDuplicateAnInjectedWorkQueue() {
        SqsClient client = mock(SqsClient.class);
        WorkQueue queue = mock(WorkQueue.class);
        contextRunner.withBean(SqsClient.class, () -> client).withBean(WorkQueue.class, () -> queue)
                .withPropertyValues(queueProperty).run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(WorkQueue.class).size());
                    assertSame(queue, context.getBean(WorkQueue.class));
                    verifyNoInteractions(client, queue);
                });
    }

    @Test
    void buildsClientWithConfiguredSqsRegionAndNoEndpointOverrideWithoutSendingRequests() {
        contextRunner.withPropertyValues(queueProperty, "bridge.sqs.region=eu-west-1").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(Region.EU_WEST_1, context.getBean(SqsClient.class).serviceClientConfiguration().region());
            assertTrue(context.getBean(SqsClient.class).serviceClientConfiguration().endpointOverride().isEmpty());
        });
    }

    @Test
    void usesSdkDefaultsWithoutSqsRegionRegardlessOfDynamoDbRegion() {
        SqsClient client = mock(SqsClient.class);
        SqsClientBuilder builder = mock(SqsClientBuilder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(client);
        try (MockedStatic<SqsClient> clients = mockStatic(SqsClient.class)) {
            clients.when(SqsClient::builder).thenReturn(builder);
            contextRunner.withPropertyValues(queueProperty, "bridge.dynamodb.region=us-east-1").run(context -> {
                assertNull(context.getStartupFailure());
                assertSame(client, context.getBean(SqsClient.class));
                verify(builder).build();
                verifyNoMoreInteractions(builder);
                verifyNoInteractions(client);
            });
        }
    }

    @Test
    void configuresLocalEndpointIndependentlyOfQueueUrl() {
        contextRunner.withPropertyValues("bridge.sqs.endpoint=http://localhost:9324", "bridge.sqs.region=us-east-1",
                "bridge.sqs.queue-url=http://localhost:9324/queue/migration-work").run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBean(WorkQueue.class) instanceof SqsWorkQueue);
                    assertEquals(URI.create("http://localhost:9324"),
                            context.getBean(SqsClient.class).serviceClientConfiguration().endpointOverride().orElseThrow());
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "relative/path", "ftp://localhost:9324", "https:///queue", "http://[invalid"})
    void rejectsInvalidEndpointWithClearError(String endpoint) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new SqsConfiguration().sqsClient("eu-west-1", endpoint));

        assertTrue(failure.getMessage().contains("bridge.sqs.endpoint must be an absolute HTTP(S) URL"));
    }

    @Test
    void explicitSqsRegionOverridesDynamoDbRegion() {
        contextRunner.withPropertyValues(queueProperty, "bridge.sqs.region=eu-west-1", "bridge.dynamodb.region=us-east-1")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(Region.EU_WEST_1, context.getBean(SqsClient.class).serviceClientConfiguration().region());
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "relative/path", "https:///queue"})
    void rejectsInvalidQueueUrl(String queueUrl) {
        SqsClient client = mock(SqsClient.class);
        contextRunner.withBean(SqsClient.class, () -> client)
                .withPropertyValues("bridge.sqs.queue-url=" + queueUrl).run(context -> {
                    assertNotNull(context.getStartupFailure());
                    verify(client).close();
                    verifyNoMoreInteractions(client);
                });
    }
}
