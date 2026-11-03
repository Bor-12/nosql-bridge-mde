package dev.borja.nosqlbridge.orchestrator;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;

import dev.borja.nosqlbridge.common.config.CommonConfiguration;
import dev.borja.nosqlbridge.common.plan.PlanLoader;
import dev.borja.nosqlbridge.common.plan.PlanValidator;
import dev.borja.nosqlbridge.common.queue.WorkQueue;
import dev.borja.nosqlbridge.common.registry.TechnologyRegistry;

@Configuration(proxyBeanMethods = false)
@Import(CommonConfiguration.class)
public class OrchestratorConfiguration {

    @Bean
    public PlanLoader planLoader(ObjectMapper objectMapper) {
        return new PlanLoader(objectMapper);
    }

    @Bean
    public PlanValidator planValidator() {
        return new PlanValidator();
    }

    @Bean
    public MigrationPartitioner migrationPartitioner(TechnologyRegistry technologyRegistry) {
        return new MigrationPartitioner(technologyRegistry);
    }

    @Bean
    public MigrationPublisher migrationPublisher(PlanValidator planValidator, MigrationPartitioner partitioner,
            ObjectProvider<WorkQueue> queues,
            @Value("${bridge.orchestrator.records-per-partition:1000}") int recordsPerPartition) {
        WorkQueue queue = queues.getIfAvailable();
        if (queue == null) {
            throw new IllegalStateException("Orchestrator requires bridge.sqs.queue-url or a WorkQueue bean.");
        }
        return new MigrationPublisher(planValidator, partitioner, queue, recordsPerPartition);
    }
}
