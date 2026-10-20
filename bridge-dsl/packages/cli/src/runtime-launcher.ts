import { spawn } from 'node:child_process';
import { stat } from 'node:fs/promises';
import * as path from 'node:path';

export class RuntimeExitError extends Error {
    constructor(public readonly exitCode: number) {
        super(`Java runtime exited with code ${exitCode}.`);
    }
}

export async function launchRuntime(planPath: string, runtimeOption?: string): Promise<void> {
    const configuredJar = runtimeOption ?? process.env.BRG_RUNTIME_JAR;
    if (!configuredJar?.trim()) {
        throw new Error('Provide a Java runtime JAR with --runtime <file> or set BRG_RUNTIME_JAR to a nonblank path.');
    }
    const runtimeJar = path.resolve(configuredJar);
    const jar = await stat(runtimeJar).catch(() => {
        throw new Error(`Cannot access runtime JAR: ${runtimeJar}. Build it with mvn package in bridge-runtime.`);
    });
    if (!jar.isFile()) {
        throw new Error(`Runtime JAR is not a file: ${runtimeJar}.`);
    }

    await new Promise<void>((resolve, reject) => {
        const child = spawn('java', ['-jar', runtimeJar, '--plan', planPath], {
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
