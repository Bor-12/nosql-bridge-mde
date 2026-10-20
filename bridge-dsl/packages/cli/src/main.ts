import type { BridgeModel } from 'bridge-language';
import { createBridgeServices, BridgeLanguageMetaData } from 'bridge-language';
import chalk from 'chalk';
import { Command } from 'commander';
import { extractAstNode, extractDocument } from './util.js';
import { writeExecutionPlan } from './execution-plan-writer.js';
import { launchRuntime, RuntimeExitError } from './runtime-launcher.js';
import { NodeFileSystem } from 'langium/node';
import * as url from 'node:url';
import * as fs from 'node:fs/promises';
import * as path from 'node:path';
const __dirname = url.fileURLToPath(new URL('.', import.meta.url));

const packagePath = path.resolve(__dirname, '..', 'package.json');
const packageContent = await fs.readFile(packagePath, 'utf-8');

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

export default async function(): Promise<void> {
    const program = new Command()
        .name('brg')
        .description('Command-line interface for the NoSQL Bridge DSL.');

    program.version(JSON.parse(packageContent).version);

    const fileExtensions = BridgeLanguageMetaData.fileExtensions.join(', ');
    program
        .command('validate')
        .argument('<file>', `source model (possible file extensions: ${fileExtensions})`)
        .description('Validates a BRG model without generating files.')
        .action(validateAction);

    program
        .command('compile')
        .argument('<file>', `source file (possible file extensions: ${fileExtensions})`)
        .option('-o, --output <file>', 'output plan file (default: <source-name>.plan.json beside the source)')
        .description('Compiles a BRG model to a BridgeExecutionPlan JSON file.')
        .action(compileAction);

    program
        .command('run')
        .argument('<file>', `source file (possible file extensions: ${fileExtensions})`)
        .option('-o, --output <file>', 'output plan file (default: <source-name>.plan.json beside the source)')
        .option('--runtime <file>', 'Java runtime JAR (takes precedence over BRG_RUNTIME_JAR)')
        .description('Compiles a BRG model and executes it with the Java runtime.')
        .action(runAction);

    try {
        await program.parseAsync(process.argv);
    } catch (error) {
        console.error(chalk.red(error instanceof Error ? error.message : String(error)));
        process.exitCode = error instanceof RuntimeExitError ? error.exitCode : 1;
    }
}
