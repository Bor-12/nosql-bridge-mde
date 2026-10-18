package dev.borja.nosqlbridge.runtime.runner;

import java.nio.file.Path;

import org.springframework.boot.CommandLineRunner;

import dev.borja.nosqlbridge.runtime.connector.ConnectorRegistry;
import dev.borja.nosqlbridge.runtime.connector.SourceConnector;
import dev.borja.nosqlbridge.runtime.connector.SourceConnectorFactory;
import dev.borja.nosqlbridge.runtime.connector.TargetConnector;
import dev.borja.nosqlbridge.runtime.connector.TargetConnectorFactory;
import dev.borja.nosqlbridge.runtime.mapping.MappingEngine;
import dev.borja.nosqlbridge.runtime.orchestration.BridgeOrchestrator;
import dev.borja.nosqlbridge.runtime.plan.BridgeExecutionPlan;
import dev.borja.nosqlbridge.runtime.plan.PlanLoader;
import dev.borja.nosqlbridge.runtime.plan.PlanValidator;

public class PlanRunner implements CommandLineRunner {

    private final Path planPath;
    private final PlanLoader planLoader;
    private final PlanValidator planValidator;
    private final ConnectorRegistry connectorRegistry;
    private final MappingEngine mappingEngine;

    public PlanRunner(Path planPath, PlanLoader planLoader, PlanValidator planValidator,
            ConnectorRegistry connectorRegistry,
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

        SourceConnectorFactory sourceFactory = connectorRegistry.sourceFactory(plan.source().technology());
        TargetConnectorFactory targetFactory = connectorRegistry.targetFactory(plan.target().technology());
        SourceConnector source = sourceFactory.create(plan.source());
        TargetConnector target = targetFactory.create(plan.target());
        BridgeOrchestrator orchestrator = new BridgeOrchestrator(source, target, mappingEngine);
        orchestrator.execute(plan);
    }

}
