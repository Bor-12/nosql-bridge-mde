package dev.borja.nosqlbridge.runtime.runner;

import java.nio.file.Path;

import org.springframework.boot.CommandLineRunner;

import dev.borja.nosqlbridge.runtime.mapping.MappingEngine;
import dev.borja.nosqlbridge.runtime.orchestration.BridgeOrchestrator;
import dev.borja.nosqlbridge.runtime.plan.BridgeExecutionPlan;
import dev.borja.nosqlbridge.runtime.plan.PlanLoader;
import dev.borja.nosqlbridge.runtime.plan.PlanValidator;
import dev.borja.nosqlbridge.runtime.registry.TechnologyRegistry;
import dev.borja.nosqlbridge.runtime.source.SourceTechnologyFactory;
import dev.borja.nosqlbridge.runtime.source.connector.SourceConnector;
import dev.borja.nosqlbridge.runtime.target.TargetConnector;
import dev.borja.nosqlbridge.runtime.target.TargetConnectorFactory;

public class PlanRunner implements CommandLineRunner {

    private final Path planPath;
    private final PlanLoader planLoader;
    private final PlanValidator planValidator;
    private final TechnologyRegistry connectorRegistry;
    private final MappingEngine mappingEngine;

    public PlanRunner(Path planPath, PlanLoader planLoader, PlanValidator planValidator,
            TechnologyRegistry connectorRegistry,
            MappingEngine mappingEngine) {
        this.planPath = planPath;
        this.planLoader = planLoader;
        this.planValidator = planValidator;
        this.connectorRegistry = connectorRegistry;
        this.mappingEngine = mappingEngine;
    }

    @Override
    public void run(String... args) throws Exception {
        BridgeExecutionPlan plan = planLoader.load(planPath);
        planValidator.validate(plan);

        SourceTechnologyFactory sourceFactory = connectorRegistry.sourceFactory(plan.source().technology());
        TargetConnectorFactory targetFactory = connectorRegistry.targetFactory(plan.target().technology());
        SourceConnector source = sourceFactory.createConnector(plan.source());
        TargetConnector target = targetFactory.create(plan.target());
        BridgeOrchestrator orchestrator = new BridgeOrchestrator(source, target, mappingEngine);
        orchestrator.execute(plan);
    }

}
