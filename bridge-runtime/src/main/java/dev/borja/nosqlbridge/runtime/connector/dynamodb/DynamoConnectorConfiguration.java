package dev.borja.nosqlbridge.runtime.connector.dynamodb;



import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DynamoProperties.class)
public class DynamoConnectorConfiguration {
    @Bean(destroyMethod = "close")
    @Lazy
    @ConditionalOnMissingBean(DynamoDbClient.class)
    public DynamoDbClient dynamoDbClient(DynamoProperties properties) {
        var builder = DynamoDbClient.builder().region(Region.of(properties.region()));
        if (properties.endpoint() != null) {
            builder.endpointOverride(properties.endpoint());
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("dummy", "dummy")));
        }
        return builder.build();
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
