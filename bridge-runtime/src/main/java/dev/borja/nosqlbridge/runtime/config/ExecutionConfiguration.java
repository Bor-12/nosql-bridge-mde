package dev.borja.nosqlbridge.runtime.config;

import java.nio.file.Path;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import dev.borja.nosqlbridge.runtime.mapping.DefaultValueConverter;
import dev.borja.nosqlbridge.runtime.execution.WorkUnitExecutor;
import dev.borja.nosqlbridge.runtime.mapping.MappingEngine;
import dev.borja.nosqlbridge.runtime.model.LogicalPathResolver;
import dev.borja.nosqlbridge.runtime.plan.PlanLoader;
import dev.borja.nosqlbridge.runtime.plan.PlanValidator;
import dev.borja.nosqlbridge.runtime.registry.TechnologyRegistry;
import dev.borja.nosqlbridge.runtime.runner.PlanRunner;
import dev.borja.nosqlbridge.runtime.source.SourceTechnologyFactory;
import dev.borja.nosqlbridge.runtime.target.TargetConnectorFactory;
import dev.borja.nosqlbridge.runtime.transformation.TransformationEngine;
import dev.borja.nosqlbridge.runtime.work.WorkUnitMessageSerializer;

@Configuration(proxyBeanMethods = false)
public class ExecutionConfiguration {

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
    @ConditionalOnProperty(name = "bridge.execution.plan")
    @ConditionalOnProperty(name = "bridge.runtime.mode", havingValue = "local", matchIfMissing = true)
    public PlanLoader planLoader(ObjectMapper objectMapper) {
        return new PlanLoader(objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "bridge.execution.plan")
    @ConditionalOnProperty(name = "bridge.runtime.mode", havingValue = "local", matchIfMissing = true)
    public PlanValidator planValidator() {
        return new PlanValidator();
    }

    @Bean
    public TechnologyRegistry connectorRegistry(List<SourceTechnologyFactory> sources,
            List<TargetConnectorFactory> targets) {
        return new TechnologyRegistry(sources, targets);
    }

    @Bean
    public MappingEngine mappingEngine() {
        return new MappingEngine(new LogicalPathResolver(), new TransformationEngine(), new DefaultValueConverter());
    }

    @Bean
    public WorkUnitExecutor workUnitExecutor(TechnologyRegistry technologyRegistry, MappingEngine mappingEngine,
            @Value("${bridge.execution.batch-size:25}") int batchSize) {
        return new WorkUnitExecutor(technologyRegistry, mappingEngine, batchSize);
    }

    @Bean
    @ConditionalOnProperty(name = "bridge.execution.plan")
    @ConditionalOnProperty(name = "bridge.runtime.mode", havingValue = "local", matchIfMissing = true)
    public PlanRunner planRunner(@Value("${bridge.execution.plan}") String planPath,
            PlanLoader planLoader, PlanValidator planValidator,
            TechnologyRegistry connectorRegistry,
            MappingEngine mappingEngine) {
        if (planPath.isBlank()) {
            throw new IllegalArgumentException("bridge.execution.plan must not be blank.");
        }
        return new PlanRunner(Path.of(planPath), planLoader, planValidator, connectorRegistry, mappingEngine);
    }
}
