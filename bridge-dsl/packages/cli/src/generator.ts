import type { BridgeModel } from 'bridge-language';
import * as fs from 'node:fs';
import { extractDestinationAndName } from './util.js';

interface SourcePlan {
    technology: string;
    [key: string]: string;
}

interface BridgeExecutionPlan {
    bridgeName: string;
    source: SourcePlan;
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

function createExecutionPlan(model: BridgeModel): BridgeExecutionPlan {
    return {
        bridgeName: model.name,
        source: createSourcePlan(model)
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