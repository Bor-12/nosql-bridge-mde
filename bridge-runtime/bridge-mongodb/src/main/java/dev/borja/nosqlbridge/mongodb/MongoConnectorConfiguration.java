package dev.borja.nosqlbridge.mongodb;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Lazy;

@AutoConfiguration
@EnableConfigurationProperties(MongoProperties.class)
public class MongoConnectorConfiguration {
    @Bean(destroyMethod = "close")
    @Lazy
    @ConditionalOnMissingBean(MongoClient.class)
    public MongoClient mongoClient(MongoProperties properties) {
        return MongoClients.create(properties.uri());
    }

    @Bean
    @ConditionalOnMissingBean(MongoLogicalValueConverter.class)
    public MongoLogicalValueConverter mongoLogicalValueConverter() {
        return new MongoLogicalValueConverter();
    }

    @Bean
    public MongoSourceTechnologyFactory mongoSourceTechnologyFactory(ObjectProvider<MongoClient> client,
            MongoLogicalValueConverter converter) {
        return new MongoSourceTechnologyFactory(client, converter);
    }
}
