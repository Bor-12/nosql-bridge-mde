package dev.borja.nosqlbridge.runtime.connector.mongodb;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

@Configuration(proxyBeanMethods = false)
public class MongoConnectorConfiguration {
    @Bean(destroyMethod = "close")
    @Lazy
    @ConditionalOnMissingBean(MongoClient.class)
    public MongoClient mongoClient(@Value("${bridge.mongodb.uri}") String uri) {
        return MongoClients.create(uri);
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
