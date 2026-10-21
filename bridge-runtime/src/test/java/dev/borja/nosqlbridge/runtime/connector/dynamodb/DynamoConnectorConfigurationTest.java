package dev.borja.nosqlbridge.runtime.connector.dynamodb;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import dev.borja.nosqlbridge.runtime.connector.TargetConnectorFactory;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DynamoConnectorConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DynamoConnectorConfiguration.class);

    @Test
    void awsUsesNoEndpointOverrideOrDummyCredentialsWithoutCallingAws() {
        contextRunner.withPropertyValues("bridge.dynamodb.region=eu-west-1").run(context -> {
            assertNull(context.getStartupFailure());
            var config = context.getBean(DynamoDbClient.class).serviceClientConfiguration();
            assertTrue(config.endpointOverride().isEmpty());
            assertEquals(Region.of("eu-west-1"), config.region());
            assertFalse(config.credentialsProvider() instanceof StaticCredentialsProvider);
            // Do not resolve credentials or send requests: the SDK owns its default chain.
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://dynamodb-local:8000", "http://127.0.0.1:8000",
            "https://dynamodb.example.org", "HTTP://dynamodb-local:8000", "HTTPS://dynamodb.example.org"})
    void explicitEndpointUsesDummyCredentials(String endpoint) {
        contextRunner.withPropertyValues("bridge.dynamodb.region=eu-west-1",
                "bridge.dynamodb.endpoint=" + endpoint).run(context -> {
            assertNull(context.getStartupFailure());
            var config = context.getBean(DynamoDbClient.class).serviceClientConfiguration();
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
