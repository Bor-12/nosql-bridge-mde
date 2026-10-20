import { createExecutionPlan, type BridgeModel } from 'bridge-language';
import * as fs from 'node:fs';
import { extractDestinationAndName } from './util.js';

export function writeExecutionPlan(
    model: BridgeModel,
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
