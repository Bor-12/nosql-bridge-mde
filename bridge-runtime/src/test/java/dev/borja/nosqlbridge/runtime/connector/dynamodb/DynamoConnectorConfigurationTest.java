package dev.borja.nosqlbridge.runtime.connector.dynamodb;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import dev.borja.nosqlbridge.runtime.connector.TargetConnectorFactory;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DynamoConnectorConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DynamoConnectorConfiguration.class);

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

        contextRunner.withBean(DynamoDbClient.class, () -> client)
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
