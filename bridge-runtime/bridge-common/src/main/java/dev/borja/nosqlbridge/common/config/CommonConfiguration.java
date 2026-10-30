package dev.borja.nosqlbridge.common.config;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import dev.borja.nosqlbridge.common.registry.TechnologyRegistry;
import dev.borja.nosqlbridge.common.source.SourceTechnologyFactory;
import dev.borja.nosqlbridge.common.target.TargetConnectorFactory;
import dev.borja.nosqlbridge.common.work.WorkUnitMessageSerializer;

@Configuration(proxyBeanMethods = false)
public class CommonConfiguration {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public WorkUnitMessageSerializer workUnitMessageSerializer(ObjectMapper objectMapper) {
        return new WorkUnitMessageSerializer(objectMapper);
    }

    @Bean
    public TechnologyRegistry connectorRegistry(List<SourceTechnologyFactory> sources,
            List<TargetConnectorFactory> targets) {
        return new TechnologyRegistry(sources, targets);
    }
}
