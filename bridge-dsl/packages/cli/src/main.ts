import type { BridgeModel } from 'bridge-language';
import { createBridgeServices, BridgeLanguageMetaData } from 'bridge-language';
import chalk from 'chalk';
import { Command } from 'commander';
import { extractAstNode, extractDocument } from './util.js';
import { generateOutput } from './generator.js';
import { NodeFileSystem } from 'langium/node';
import * as url from 'node:url';
import * as fs from 'node:fs/promises';
import * as path from 'node:path';
const __dirname = url.fileURLToPath(new URL('.', import.meta.url));

const packagePath = path.resolve(__dirname, '..', 'package.json');
const packageContent = await fs.readFile(packagePath, 'utf-8');

export const generateAction = async (source: string, destination: string): Promise<void> => {
    const services = createBridgeServices(NodeFileSystem).Bridge;
    const model = await extractAstNode<BridgeModel>(source, services);
    const generatedFilePath = generateOutput(model, source, destination);
    console.log(chalk.green(`Code generated succesfully: ${generatedFilePath}`));
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
        .command('generate')
        .argument('<file>', `source file (possible file extensions: ${fileExtensions})`)
        .argument('<destination>', 'destination file')
        .description('Generates code for a provided source file.')
        .action(generateAction);

    try {
        await program.parseAsync(process.argv);
    } catch (error) {
        console.error(chalk.red(error instanceof Error ? error.message : String(error)));
        process.exitCode = 1;
    }
}
