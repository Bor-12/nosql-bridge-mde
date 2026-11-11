import type { BridgeModel } from 'bridge-language';
import { createBridgeServices } from 'bridge-language';
import chalk from 'chalk';
import { NodeFileSystem } from 'langium/node';
import * as path from 'node:path';
import { extractAstNode, extractDocument } from './util.js';
import { writeExecutionPlan } from './execution-plan-writer.js';
import { loadRunConfig } from './execution/config.js';
import { runDocker, type RuntimeImages } from './execution/docker.js';

// Internal E2E input only, not a public runtime selection option.
function readTestRuntimeImages(): RuntimeImages | undefined {
    const worker = process.env.BRG_TEST_WORKER_IMAGE;
    const orchestrator = process.env.BRG_TEST_ORCHESTRATOR_IMAGE;
    if (worker === undefined && orchestrator === undefined) return undefined;
    if (worker === undefined || orchestrator === undefined) {
        throw new Error('Internal E2E images require both BRG_TEST_WORKER_IMAGE and BRG_TEST_ORCHESTRATOR_IMAGE.');
    }
    const component = '[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*';
    const registry = '(?:[a-z0-9]+(?:[.-][a-z0-9]+)*(?::[0-9]+)?/)?';
    const reference = new RegExp(`^${registry}${component}(?:/${component})*(?::[A-Za-z0-9_][A-Za-z0-9_.-]{0,127})?(?:@sha256:[a-f0-9]{64})?$`);
    for (const [name, value] of [['BRG_TEST_WORKER_IMAGE', worker], ['BRG_TEST_ORCHESTRATOR_IMAGE', orchestrator]]) {
        if (value.trim() !== value || !reference.test(value)) throw new Error(`Invalid Docker image reference in ${name}.`);
    }
    return { worker, orchestrator };
}

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

export const runAction = async (source: string, options: { output?: string; config?: string; logsDir?: string } = {}): Promise<void> => {
    const images = readTestRuntimeImages();
    const config = await loadRunConfig(options.config ?? 'bridge.yaml');
    const model = await loadModel(source);
    const planPath = compilePlan(model, source, options.output);
    console.log('COMPILED');
    const controller = new AbortController();
    const cancel = () => controller.abort();
    process.once('SIGINT', cancel);
    process.once('SIGTERM', cancel);
    try {
        await runDocker(planPath, config, { images, logsDir: options.logsDir, signal: AbortSignal.any([controller.signal, AbortSignal.timeout(config.timeoutSeconds * 1000)]) });
    } finally {
        process.off('SIGINT', cancel);
        process.off('SIGTERM', cancel);
    }
};

export const validateAction = async (source: string): Promise<void> => {
    const services = createBridgeServices(NodeFileSystem).Bridge;
    await extractDocument(source, services);
    console.log(chalk.green(`Model is valid: ${source}`));
};
