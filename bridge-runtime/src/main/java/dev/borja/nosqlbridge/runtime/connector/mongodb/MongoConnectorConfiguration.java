package dev.borja.nosqlbridge.runtime.connector.mongodb;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

@Configuration(proxyBeanMethods = false)
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
    public MongoSourceConnectorFactory mongoSourceConnectorFactory(ObjectProvider<MongoClient> client,
            MongoLogicalValueConverter converter) {
        return new MongoSourceConnectorFactory(client, converter);
    }
}
