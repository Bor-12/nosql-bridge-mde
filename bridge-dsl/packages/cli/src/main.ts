import { BridgeLanguageMetaData } from 'bridge-language';
import chalk from 'chalk';
import { Command } from 'commander';
import { compileAction, runAction, validateAction } from './actions.js';
import { runtimeInstallAction, runtimeStatusAction } from './runtime/runtime-manager.js';
import { RuntimeExitError } from './runtime-launcher.js';
import * as url from 'node:url';
import * as fs from 'node:fs/promises';
import * as path from 'node:path';
const __dirname = url.fileURLToPath(new URL('.', import.meta.url));

const packagePath = path.resolve(__dirname, '..', 'package.json');
const packageContent = await fs.readFile(packagePath, 'utf-8');

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
        .requiredOption('--config <file>', 'external infrastructure configuration (bridge.yml)')
        .option('--runtime <file>', 'Java runtime JAR (takes precedence over BRG_RUNTIME_JAR)')
        .description('Compiles a BRG model and executes it with the Java runtime.')
        .action(runAction);

    const runtime = program.command('runtime').description('Install or inspect the compatible Java runtime.');
    runtime.command('install').action(runtimeInstallAction);
    runtime.command('status').action(runtimeStatusAction);

    try {
        await program.parseAsync(process.argv);
    } catch (error) {
        console.error(chalk.red(error instanceof Error ? error.message : String(error)));
        process.exitCode = error instanceof RuntimeExitError ? error.exitCode : 1;
    }
}
