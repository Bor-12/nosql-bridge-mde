package dev.borja.nosqlbridge.runtime.config;

import java.nio.file.Path;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.borja.nosqlbridge.runtime.connector.ConnectorRegistry;
import dev.borja.nosqlbridge.runtime.connector.SourceConnectorFactory;
import dev.borja.nosqlbridge.runtime.connector.TargetConnectorFactory;
import dev.borja.nosqlbridge.runtime.mapping.DefaultValueConverter;
import dev.borja.nosqlbridge.runtime.mapping.MappingEngine;
import dev.borja.nosqlbridge.runtime.model.LogicalPathResolver;
import dev.borja.nosqlbridge.runtime.plan.PlanLoader;
import dev.borja.nosqlbridge.runtime.plan.PlanValidator;
import dev.borja.nosqlbridge.runtime.runner.PlanRunner;
import dev.borja.nosqlbridge.runtime.transformation.TransformationEngine;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "bridge.execution.plan")
public class ExecutionConfiguration {

    @Bean
    public PlanLoader planLoader() {
        return new PlanLoader(new ObjectMapper());
    }

    @Bean
    public PlanValidator planValidator() {
        return new PlanValidator();
    }

    @Bean
    public ConnectorRegistry connectorRegistry(List<SourceConnectorFactory> sources,
            List<TargetConnectorFactory> targets) {
        return new ConnectorRegistry(sources, targets);
    }

    @Bean
    public MappingEngine mappingEngine() {
        return new MappingEngine(new LogicalPathResolver(), new TransformationEngine(), new DefaultValueConverter());
    }

    @Bean
    public PlanRunner planRunner(@Value("${bridge.execution.plan}") String planPath,
            PlanLoader planLoader, PlanValidator planValidator,
            ConnectorRegistry connectorRegistry,
            MappingEngine mappingEngine) {
        if (planPath.isBlank()) {
            throw new IllegalArgumentException("bridge.execution.plan must not be blank.");
        }
        return new PlanRunner(Path.of(planPath), planLoader, planValidator, connectorRegistry, mappingEngine);
    }
}
