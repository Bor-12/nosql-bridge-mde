import { spawn } from 'node:child_process';
import { readableFile, resolveRuntime } from './runtime/runtime-manager.js';
import * as path from 'node:path';

export class RuntimeExitError extends Error {
    constructor(public readonly exitCode: number) {
        super(`Java runtime exited with code ${exitCode}.`);
    }
}

export async function launchRuntime(planPath: string, configPath: string, runtimeOption?: string): Promise<void> {
    const config = await readableFile(configPath, 'Config file');
    const runtimeJar = await resolveRuntime(runtimeOption);
    await new Promise<void>((resolve, reject) => {
        const child = spawn('java', ['-jar', runtimeJar, '--plan', path.resolve(planPath), '--config', config], {
            stdio: 'inherit',
            shell: false
        });
        child.once('error', error => {
            reject(new Error(`Could not start Java runtime: ${error.message}. Check that Java 21 is available on PATH.`));
        });
        child.once('close', (code, signal) => {
            if (signal) {
                reject(new Error(`Java runtime terminated by signal ${signal}.`));
            } else if (code !== 0) {
                reject(code === null ? new Error('Java runtime exited without an exit code.') : new RuntimeExitError(code));
            } else {
                resolve();
            }
        });
    });
}
