import type { BridgeModel } from 'bridge-language';
import { createBridgeServices } from 'bridge-language';
import chalk from 'chalk';
import { NodeFileSystem } from 'langium/node';
import * as path from 'node:path';
import { extractAstNode, extractDocument } from './util.js';
import { writeExecutionPlan } from './execution-plan-writer.js';
import { launchRuntime } from './runtime-launcher.js';

async function loadModel(source: string): Promise<BridgeModel> {
    const services = createBridgeServices(NodeFileSystem).Bridge;
    return extractAstNode<BridgeModel>(source, services);
}

function compilePlan(model: BridgeModel, source: string, output?: string): string {
    const destination = output ?? path.join(
        path.dirname(source),
        `${path.basename(source, path.extname(source))}.plan.json`
    );
    const planPath = writeExecutionPlan(model, destination);
    console.log(chalk.green(`Compiled execution plan: ${planPath}`));
    return planPath;
}

export const compileAction = async (source: string, options: { output?: string } = {}): Promise<void> => {
    const model = await loadModel(source);
    compilePlan(model, source, options.output);
};

export const runAction = async (source: string, options: { output?: string; runtime?: string } = {}): Promise<void> => {
    const model = await loadModel(source);
    const planPath = compilePlan(model, source, options.output);
    await launchRuntime(planPath, options.runtime);
};

export const validateAction = async (source: string): Promise<void> => {
    const services = createBridgeServices(NodeFileSystem).Bridge;
    await extractDocument(source, services);
    console.log(chalk.green(`Model is valid: ${source}`));
};
