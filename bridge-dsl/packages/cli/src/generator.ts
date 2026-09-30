import type { BridgeModel, FieldMapping, FieldPath } from 'bridge-language';
import * as fs from 'node:fs';
import { extractDestinationAndName } from './util.js';

interface SourcePlan {
    technology: string;
    [key: string]: string;
}

interface TargetPlan {
    technology: string;
    [key: string]: string;
}

interface MappingPlan {
    source: string;
    target: string;
}

interface BridgeExecutionPlan {
    bridgeName: string;
    source: SourcePlan;
    target: TargetPlan;
    mappings: MappingPlan[];
}

function createSourcePlan(model: BridgeModel): SourcePlan {
    const sourcePlan: SourcePlan = {
        technology: model.source.technology.toLowerCase()
    };

    for (const property of model.source.properties) {
        sourcePlan[property.name] = property.value;
    }

    return sourcePlan;
}

function createTargetPlan(model: BridgeModel): TargetPlan {
    const targetPlan: TargetPlan = {
        technology: model.target.technology.toLowerCase()
    };

    for (const property of model.target.properties) {
        targetPlan[property.name] = property.value;
    }

    return targetPlan;
}

function fieldPathToString(path: FieldPath): string {
    return path.parts.join('.');
}

function createMappingPlan(mapping: FieldMapping): MappingPlan {
    return {
        source: fieldPathToString(mapping.source),
        target: mapping.target
    };
}

function createExecutionPlan(model: BridgeModel): BridgeExecutionPlan {
    return {
        bridgeName: model.name,
        source: createSourcePlan(model),
        target: createTargetPlan(model),
        mappings: model.mapping.mappings.map(createMappingPlan)
    };
}

export function generateOutput(
    model: BridgeModel,
    source: string,
    destination: string
): string {
    const data = extractDestinationAndName(destination);

    const executionPlan = createExecutionPlan(model);
    const content = JSON.stringify(executionPlan, null, 2);

    if (!fs.existsSync(data.destination)) {
        fs.mkdirSync(data.destination, { recursive: true });
    }

    fs.writeFileSync(destination, `${content}\n`, 'utf8');

    return destination;
}
