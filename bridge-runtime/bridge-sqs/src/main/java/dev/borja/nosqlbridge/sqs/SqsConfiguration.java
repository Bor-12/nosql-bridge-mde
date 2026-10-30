package dev.borja.nosqlbridge.sqs;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Lazy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

import dev.borja.nosqlbridge.common.queue.WorkQueue;
import dev.borja.nosqlbridge.common.work.WorkUnitMessageSerializer;

@AutoConfiguration
@ConditionalOnProperty(name = "bridge.sqs.queue-url")
public class SqsConfiguration {

    @Bean(destroyMethod = "close")
    @Lazy
    @ConditionalOnMissingBean(SqsClient.class)
    public SqsClient sqsClient(@Value("${bridge.sqs.region:}") String region,
            @Value("${bridge.sqs.endpoint:}") String endpoint) {
        SqsClientBuilder builder = SqsClient.builder();
        if (!region.isBlank()) {
            builder.region(Region.of(region));
        }
        if (!endpoint.isEmpty()) {
            URI address;
            try {
                address = URI.create(endpoint);
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("bridge.sqs.endpoint must be an absolute HTTP(S) URL.", failure);
            }
            if (address.getHost() == null || !("https".equalsIgnoreCase(address.getScheme())
                    || "http".equalsIgnoreCase(address.getScheme()))) {
                throw new IllegalArgumentException("bridge.sqs.endpoint must be an absolute HTTP(S) URL.");
            }
            builder.endpointOverride(address);
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean(WorkQueue.class)
    public WorkQueue workQueue(SqsClient client, WorkUnitMessageSerializer serializer,
            @Value("${bridge.sqs.queue-url}") String queueUrl) {
        return new SqsWorkQueue(client, serializer, queueUrl);
    }
}
