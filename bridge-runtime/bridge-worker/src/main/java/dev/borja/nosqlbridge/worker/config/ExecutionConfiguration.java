package dev.borja.nosqlbridge.worker.config;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;

import dev.borja.nosqlbridge.common.config.CommonConfiguration;
import dev.borja.nosqlbridge.worker.mapping.DefaultValueConverter;
import dev.borja.nosqlbridge.worker.execution.WorkUnitExecutor;
import dev.borja.nosqlbridge.worker.mapping.MappingEngine;
import dev.borja.nosqlbridge.worker.model.LogicalPathResolver;
import dev.borja.nosqlbridge.common.plan.PlanLoader;
import dev.borja.nosqlbridge.common.plan.PlanValidator;
import dev.borja.nosqlbridge.common.registry.TechnologyRegistry;
import dev.borja.nosqlbridge.worker.runner.PlanRunner;
import dev.borja.nosqlbridge.worker.transformation.TransformationEngine;

@Configuration(proxyBeanMethods = false)
@Import(CommonConfiguration.class)
public class ExecutionConfiguration {

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
