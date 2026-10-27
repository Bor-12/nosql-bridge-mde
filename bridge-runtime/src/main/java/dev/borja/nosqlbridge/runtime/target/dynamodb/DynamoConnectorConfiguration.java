package dev.borja.nosqlbridge.runtime.target.dynamodb;



import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({DynamoProperties.class, DynamoBatchWriteProperties.class})
public class DynamoConnectorConfiguration {
    @Bean(destroyMethod = "close")
    @Lazy
    @ConditionalOnMissingBean(DynamoDbClient.class)
    public DynamoDbClient dynamoDbClient(DynamoProperties properties) {
        DynamoDbClientBuilder builder = DynamoDbClient.builder().region(Region.of(properties.region()));
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
            DynamoLogicalValueConverter converter, DynamoBatchWriteProperties batchWriteProperties) {
        return new DynamoTargetConnectorFactory(client, converter, batchWriteProperties);
    }
}
