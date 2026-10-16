package dev.borja.nosqlbridge.runtime.connector.dynamodb;

import java.net.URI;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Configuration(proxyBeanMethods = false)
public class DynamoConnectorConfiguration {
    @Bean(destroyMethod = "close")
    @Lazy
    @ConditionalOnMissingBean(DynamoDbClient.class)
    public DynamoDbClient dynamoDbClient(@Value("${bridge.dynamodb.endpoint}") String endpoint,
            @Value("${bridge.dynamodb.region}") String region) {
        return DynamoDbClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("dummy", "dummy")))
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(DynamoLogicalValueConverter.class)
    public DynamoLogicalValueConverter dynamoLogicalValueConverter() {
        return new DynamoLogicalValueConverter();
    }

    @Bean
    public DynamoTargetConnectorFactory dynamoTargetConnectorFactory(ObjectProvider<DynamoDbClient> client,
            DynamoLogicalValueConverter converter) {
        return new DynamoTargetConnectorFactory(client, converter);
    }
}
