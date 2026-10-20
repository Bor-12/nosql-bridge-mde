import { copyFileSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import * as path from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import main, { compileAction, runAction } from '../src/main.js';
import * as runtime from '../src/runtime-launcher.js';
import * as writer from '../src/execution-plan-writer.js';

const fixtures = fileURLToPath(new URL('../../../test-inputs/', import.meta.url));
let directory: string;
let source: string;
const originalArgv = process.argv;
const originalExitCode = process.exitCode;

beforeEach(() => {
    directory = mkdtempSync(path.join(tmpdir(), 'brg-run-'));
    source = path.join(directory, 'model with spaces.brg');
    copyFileSync(path.join(fixtures, 'minimal.brg'), source);
    vi.spyOn(runtime, 'launchRuntime').mockResolvedValue();
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
});

afterEach(() => {
    vi.restoreAllMocks();
    process.argv = originalArgv;
    process.exitCode = originalExitCode;
    rmSync(directory, { recursive: true, force: true });
});

async function runCli(...args: string[]) {
    process.argv = [process.execPath, 'brg', ...args];
    process.exitCode = undefined;
    await main();
    return process.exitCode;
}

describe('brg run', () => {
    it('retains the default plan and produces exactly the same JSON as compile', async () => {
        const output = path.join(directory, 'model with spaces.plan.json');
        const compiled = path.join(directory, 'compiled.json');
        vi.mocked(runtime.launchRuntime).mockImplementation(async plan => {
            expect(JSON.parse(readFileSync(plan, 'utf8')).bridgeName).toBe('ProductBridge');
        });

        expect(await runCli('run', source)).toBeUndefined();
        await compileAction(source, { output: compiled });

        expect(runtime.launchRuntime).toHaveBeenCalledExactlyOnceWith(output, undefined);
        expect(readFileSync(output, 'utf8')).toBe(readFileSync(compiled, 'utf8'));
        expect(readdirSync(directory).sort()).toEqual([
            'compiled.json', 'model with spaces.brg', 'model with spaces.plan.json'
        ]);
    });

    it.each(['-o', '--output'])('writes and retains the plan at the exact nested path selected with %s', async option => {
        const output = path.join(directory, 'new plans', 'custom.plan.json');

        expect(await runCli('run', source, option, output)).toBeUndefined();

        expect(runtime.launchRuntime).toHaveBeenCalledExactlyOnceWith(output, undefined);
        expect(JSON.parse(readFileSync(output, 'utf8')).bridgeName).toBe('ProductBridge');
        expect(readdirSync(directory).sort()).toEqual(['model with spaces.brg', 'new plans']);
    });

    it('combines output and runtime selection without changing the relative plan path', async () => {
        const output = path.relative(process.cwd(), path.join(directory, 'plans', 'custom.plan.json'));
        const runtimeJar = path.join(directory, 'selected runtime.jar');

        expect(await runCli('run', source, '-o', output, '--runtime', runtimeJar)).toBeUndefined();

        expect(runtime.launchRuntime).toHaveBeenCalledExactlyOnceWith(output, runtimeJar);
        expect(JSON.parse(readFileSync(output, 'utf8')).planVersion).toBe(1);
        expect(readdirSync(directory).sort()).toEqual(['model with spaces.brg', 'plans']);
    });

    it('passes the public runtime option and the default plan path to the launcher', async () => {
        const runtimeJar = path.join(directory, 'selected runtime.jar');
        const output = path.join(directory, 'model with spaces.plan.json');

        expect(await runCli('run', source, '--runtime', runtimeJar)).toBeUndefined();

        expect(runtime.launchRuntime).toHaveBeenCalledExactlyOnceWith(output, runtimeJar);
        expect(JSON.parse(readFileSync(output, 'utf8')).planVersion).toBe(1);
    });

    it.each(['semantic', 'syntax', 'missing', 'extension'])('rejects %s errors before writing or launching', async kind => {
        if (kind === 'semantic') {
            copyFileSync(path.join(fixtures, 'default-errors.brg'), source);
        } else if (kind === 'syntax') {
            writeFileSync(source, readFileSync(source, 'utf8').trimEnd().slice(0, -1));
        } else if (kind === 'missing') {
            source = path.join(directory, 'missing.brg');
        } else {
            source = path.join(directory, 'model.txt');
            copyFileSync(path.join(fixtures, 'minimal.brg'), source);
        }
        const files = readdirSync(directory);

        expect(await runCli('run', source)).toBe(1);

        expect(runtime.launchRuntime).not.toHaveBeenCalled();
        expect(readdirSync(directory)).toEqual(files);
        expect(console.error).toHaveBeenCalled();
        expect(console.log).not.toHaveBeenCalled();
    });

    it('preserves an existing plan on validation failure', async () => {
        copyFileSync(path.join(fixtures, 'default-errors.brg'), source);
        const output = path.join(directory, 'model with spaces.plan.json');
        writeFileSync(output, 'previous plan');

        await expect(runAction(source)).rejects.toThrow('validation errors');

        expect(runtime.launchRuntime).not.toHaveBeenCalled();
        expect(readFileSync(output, 'utf8')).toBe('previous plan');
    });

    it('does not launch Java if writing the plan fails', async () => {
        vi.spyOn(writer, 'writeExecutionPlan').mockImplementation(() => {
            throw new Error('Cannot write plan');
        });

        expect(await runCli('run', source)).toBe(1);

        expect(runtime.launchRuntime).not.toHaveBeenCalled();
        expect(console.error).toHaveBeenCalledWith(expect.stringContaining('Cannot write plan'));
        expect(readdirSync(directory)).toEqual(['model with spaces.brg']);
        expect(console.log).not.toHaveBeenCalled();
    });

    it('rejects a compiled plan as input without invoking the runtime or modifying the plan', async () => {
        const plan = path.join(directory, 'model with spaces.plan.json');
        await compileAction(source);
        const content = readFileSync(plan, 'utf8');
        vi.mocked(console.log).mockClear();

        expect(await runCli('run', plan)).toBe(1);

        expect(runtime.launchRuntime).not.toHaveBeenCalled();
        expect(console.error).toHaveBeenCalledWith(expect.stringContaining('Please choose a file with one of these extensions: .brg.'));
        expect(console.log).not.toHaveBeenCalled();
        expect(readFileSync(plan, 'utf8')).toBe(content);
        expect(readdirSync(directory).sort()).toEqual(['model with spaces.brg', 'model with spaces.plan.json']);
    });

    it('propagates the runtime exit code and retains the generated plan', async () => {
        vi.mocked(runtime.launchRuntime).mockRejectedValue(new runtime.RuntimeExitError(7));

        expect(await runCli('run', source)).toBe(7);

        expect(console.error).toHaveBeenCalledWith(expect.stringContaining('Java runtime exited with code 7.'));
        expect(JSON.parse(readFileSync(path.join(directory, 'model with spaces.plan.json'), 'utf8')).planVersion).toBe(1);
        expect(console.log).toHaveBeenCalledExactlyOnceWith(expect.stringContaining('Compiled execution plan:'));
    });

    it.each([
        { failure: new runtime.RuntimeExitError(7), exitCode: 7 },
        { failure: new Error('Could not start Java runtime: ENOENT'), exitCode: 1 }
    ])('retains a custom output plan after $failure.message', async ({ failure, exitCode }) => {
        const output = path.join(directory, 'plans', 'custom.plan.json');
        vi.mocked(runtime.launchRuntime).mockRejectedValue(failure);

        expect(await runCli('run', source, '--output', output)).toBe(exitCode);

        expect(runtime.launchRuntime).toHaveBeenCalledExactlyOnceWith(output, undefined);
        expect(JSON.parse(readFileSync(output, 'utf8')).planVersion).toBe(1);
        expect(console.error).toHaveBeenCalledWith(expect.stringContaining(failure.message));
    });

    it('reports launch failure and retains the generated plan', async () => {
        vi.mocked(runtime.launchRuntime).mockRejectedValue(new Error('Could not start Java runtime: ENOENT'));

        expect(await runCli('run', source)).toBe(1);

        expect(console.error).toHaveBeenCalledWith(expect.stringContaining('Could not start Java runtime: ENOENT'));
        expect(JSON.parse(readFileSync(path.join(directory, 'model with spaces.plan.json'), 'utf8')).planVersion).toBe(1);
    });
});
