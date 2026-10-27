package dev.borja.nosqlbridge.runtime.target.dynamodb;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbServiceClientConfiguration;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;

import dev.borja.nosqlbridge.runtime.model.LogicalRecord;
import dev.borja.nosqlbridge.runtime.model.LogicalValue;
import dev.borja.nosqlbridge.runtime.plan.ConnectorPlan;
import dev.borja.nosqlbridge.runtime.target.TargetConnectorFactory;
import dev.borja.nosqlbridge.runtime.target.TargetConnector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DynamoConnectorConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DynamoConnectorConfiguration.class);

    @Test
    void bindsBatchPolicyAndPassesItThroughFactoryToConnector() {
        DynamoDbClient client = mock(DynamoDbClient.class);
        when(client.batchWriteItem(any(BatchWriteItemRequest.class))).thenAnswer(invocation ->
                BatchWriteItemResponse.builder().unprocessedItems(
                        ((BatchWriteItemRequest) invocation.getArgument(0)).requestItems()).build());

        contextRunner.withBean(DynamoDbClient.class, () -> client)
                .withPropertyValues("bridge.dynamodb.region=eu-west-1",
                        "bridge.dynamodb.batch-write.max-retries=0",
                        "bridge.dynamodb.batch-write.initial-backoff=7ms",
                        "bridge.dynamodb.batch-write.max-backoff=30ms")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    DynamoBatchWriteProperties properties = context.getBean(DynamoBatchWriteProperties.class);
                    assertEquals(0, properties.maxRetries());
                    assertEquals(Duration.ofMillis(7), properties.initialBackoff());
                    assertEquals(Duration.ofMillis(30), properties.maxBackoff());
                    TargetConnector connector = context.getBean(TargetConnectorFactory.class).create(new ConnectorPlan("dynamodb",
                            Map.of("table", "Products", "partitionKey", "id")));
                    assertThrows(IllegalStateException.class, () -> connector.writeBatch(List.of(
                            new LogicalRecord(Map.of("id", new LogicalValue.StringValue("A"))))));
                    verify(client).batchWriteItem(any(BatchWriteItemRequest.class));
                });
    }

    @Test
    void rejectsInvalidBatchPolicyDuringConfigurationBinding() {
        contextRunner.withPropertyValues("bridge.dynamodb.region=eu-west-1",
                        "bridge.dynamodb.batch-write.max-retries=-1")
                .run(context -> assertTrue(context.getStartupFailure() != null));
    }

    @Test
    void awsUsesNoEndpointOverrideOrDummyCredentialsWithoutCallingAws() {
        contextRunner.withPropertyValues("bridge.dynamodb.region=eu-west-1").run(context -> {
            assertNull(context.getStartupFailure());
            DynamoDbServiceClientConfiguration config = context.getBean(DynamoDbClient.class).serviceClientConfiguration();
            assertTrue(config.endpointOverride().isEmpty());
            assertEquals(Region.of("eu-west-1"), config.region());
            assertFalse(config.credentialsProvider() instanceof StaticCredentialsProvider);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://dynamodb-local:8000", "http://127.0.0.1:8000",
            "https://dynamodb.example.org", "HTTP://dynamodb-local:8000", "HTTPS://dynamodb.example.org"})
    void explicitEndpointUsesDummyCredentials(String endpoint) {
        contextRunner.withPropertyValues("bridge.dynamodb.region=eu-west-1",
                "bridge.dynamodb.endpoint=" + endpoint).run(context -> {
            assertNull(context.getStartupFailure());
            DynamoDbServiceClientConfiguration config = context.getBean(DynamoDbClient.class).serviceClientConfiguration();
            assertEquals(URI.create(endpoint), config.endpointOverride().orElseThrow());
            assertEquals(Region.of("eu-west-1"), config.region());
            assertTrue(config.credentialsProvider() instanceof StaticCredentialsProvider);
            AwsCredentialsIdentity credentials = config.credentialsProvider().resolveIdentity().join();
            assertEquals("dummy", credentials.accessKeyId());
            assertEquals("dummy", credentials.secretAccessKey());
        });
    }

    @Test
    void rejectsMissingRegion() {
        contextRunner.run(context -> assertTrue(context.getStartupFailure() != null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"relative/path", "http:///missing-host", "ftp://dynamodb-local:8000", "http://[invalid"})
    void rejectsInvalidEndpoint(String endpoint) {
        contextRunner.withPropertyValues("bridge.dynamodb.region=eu-west-1",
                "bridge.dynamodb.endpoint=" + endpoint).run(context -> assertTrue(context.getStartupFailure() != null));
    }

    @Test
    void registersDefaultBeansWithLocalEndpointRegionAndDummyCredentials() {
        contextRunner.withPropertyValues("bridge.dynamodb.endpoint=http://localhost:8001",
                "bridge.dynamodb.region=eu-west-1").run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(DynamoDbClient.class).size());
                    assertEquals(1, context.getBeansOfType(DynamoLogicalValueConverter.class).size());
                    assertEquals(1, context.getBeansOfType(DynamoTargetConnectorFactory.class).size());
                    assertSame(context.getBean(DynamoTargetConnectorFactory.class),
                            context.getBean(TargetConnectorFactory.class));

                    DynamoDbClient client = context.getBean(DynamoDbClient.class);
                    assertEquals(URI.create("http://localhost:8001"),
                            client.serviceClientConfiguration().endpointOverride().orElseThrow());
                    assertEquals(Region.of("eu-west-1"), client.serviceClientConfiguration().region());
                    assertTrue(client.serviceClientConfiguration().credentialsProvider() instanceof StaticCredentialsProvider);
                    AwsCredentialsIdentity credentials = client.serviceClientConfiguration()
                            .credentialsProvider().resolveIdentity().join();
                    assertEquals("dummy", credentials.accessKeyId());
                    assertEquals("dummy", credentials.secretAccessKey());
                });
    }

    @Test
    void usesAlternativeClientAndConverterWithoutDuplicatesOrConnectionProperties() {
        DynamoDbClient client = mock(DynamoDbClient.class);
        DynamoLogicalValueConverter converter = new DynamoLogicalValueConverter();

        contextRunner.withPropertyValues("bridge.dynamodb.region=eu-west-1").withBean(DynamoDbClient.class, () -> client)
                .withBean(DynamoLogicalValueConverter.class, () -> converter)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(DynamoDbClient.class).size());
                    assertEquals(1, context.getBeansOfType(DynamoLogicalValueConverter.class).size());
                    assertEquals(1, context.getBeansOfType(DynamoTargetConnectorFactory.class).size());
                    assertSame(client, context.getBean(DynamoDbClient.class));
                    assertSame(converter, context.getBean(DynamoLogicalValueConverter.class));
                    verifyNoInteractions(client);
                });
    }
}
