import type { BridgeModel } from 'bridge-language';
import { createBridgeServices, createExecutionPlan } from 'bridge-language';
import { clearDocuments } from 'langium/test';
import { NodeFileSystem } from 'langium/node';
import { copyFileSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import * as path from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { compileAction, runAction, validateAction } from '../src/actions.js';
import * as runtime from '../src/runtime-launcher.js';
import * as writer from '../src/execution-plan-writer.js';
import { extractAstNode, extractDocument } from '../src/util.js';

const fixtures = fileURLToPath(new URL('../../../test-inputs/', import.meta.url));
let directory: string;
let services: ReturnType<typeof createBridgeServices>;

beforeEach(() => {
    directory = mkdtempSync(path.join(tmpdir(), 'brg-actions-'));
    services = createBridgeServices(NodeFileSystem);
});

afterEach(async () => {
    vi.restoreAllMocks();
    await clearDocuments(services.shared);
    rmSync(directory, { recursive: true, force: true });
});

function copyFixture(name = 'minimal.brg'): string {
    const destination = path.join(directory, name);
    copyFileSync(path.join(fixtures, name), destination);
    return destination;
}

describe('Document loading utilities', () => {
    it('returns the validated document and its AST using the same services', async () => {
        const source = copyFixture();

        const document = await extractDocument(source, services.Bridge);
        const model = await extractAstNode<BridgeModel>(source, services.Bridge);

        expect(document.diagnostics).toEqual([]);
        expect(document.textDocument.getText()).toBe(readFileSync(source, 'utf8'));
        expect(model).toBe(document.parseResult.value);
        expect(model.name).toBe('ProductBridge');
    });

    it('rejects with every semantic diagnostic so callers can handle validation failure', async () => {
        const source = copyFixture('default-errors.brg');

        await expect(extractDocument(source, services.Bridge)).rejects.toThrow(
            /There are validation errors:\n(?:line \d+: .*\n){4}line \d+: Unknown transformation 'UnknownTransformation'\./
        );
        expect(readdirSync(directory)).toEqual(['default-errors.brg']);
    });

    it.each([
        { file: 'missing.brg', message: 'does not exist.' },
        { file: 'model.txt', message: 'Please choose a file with one of these extensions: .brg.' }
    ])('rejects $file with a reusable error', async ({ file, message }) => {
        await expect(extractDocument(path.join(directory, file), services.Bridge)).rejects.toThrow(message);
    });
});

describe('CLI actions', () => {
    it('resolves validation without output files and reports the validated source', async () => {
        const source = copyFixture();
        const log = vi.spyOn(console, 'log').mockImplementation(() => {});

        await expect(validateAction(source)).resolves.toBeUndefined();

        expect(log).toHaveBeenCalledOnce();
        expect(log).toHaveBeenCalledWith(expect.stringContaining(`Model is valid: ${source}`));
        expect(readdirSync(directory)).toEqual(['minimal.brg']);
    });

    it.each(['existing', 'nested/new'])('writes a serialized execution plan into %s', async folder => {
        const source = copyFixture();
        const output = folder === 'existing'
            ? path.join(directory, 'plan.json')
            : path.join(directory, folder, 'plan.json');
        if (folder === 'existing') {
            writeFileSync(output, 'previous plan');
        }
        const log = vi.spyOn(console, 'log').mockImplementation(() => {});

        await compileAction(source, { output });

        const content = readFileSync(output, 'utf8');
        expect(content.endsWith('\n')).toBe(true);
        const model = await extractAstNode<BridgeModel>(source, services.Bridge);
        expect(JSON.parse(content)).toEqual(createExecutionPlan(model));
        expect(log).toHaveBeenCalledWith(expect.stringContaining(`Compiled execution plan: ${output}`));
    });

    it('propagates a filesystem error without reporting compilation success', async () => {
        const source = copyFixture();
        const blocker = path.join(directory, 'blocked');
        writeFileSync(blocker, 'keep this file');
        const log = vi.spyOn(console, 'log').mockImplementation(() => {});

        await expect(compileAction(source, { output: path.join(blocker, 'plan.json') })).rejects.toThrow();

        expect(log).not.toHaveBeenCalled();
        expect(readFileSync(blocker, 'utf8')).toBe('keep this file');
        expect(readdirSync(directory).sort()).toEqual(['blocked', 'minimal.brg']);
    });

    it('preserves an existing output plan when model validation fails', async () => {
        const source = copyFixture('default-errors.brg');
        const output = path.join(directory, 'previous.plan.json');
        writeFileSync(output, 'previous plan');
        const log = vi.spyOn(console, 'log').mockImplementation(() => {});

        await expect(compileAction(source, { output })).rejects.toThrow('There are validation errors:');

        expect(readFileSync(output, 'utf8')).toBe('previous plan');
        expect(log).not.toHaveBeenCalled();
    });
});

describe('Run action', () => {
    let source: string;

    beforeEach(() => {
        source = path.join(directory, 'model with spaces.brg');
        copyFileSync(path.join(fixtures, 'minimal.brg'), source);
        vi.spyOn(runtime, 'launchRuntime').mockResolvedValue();
        vi.spyOn(console, 'log').mockImplementation(() => {});
    });

    it('retains the default plan and produces exactly the same JSON as compile', async () => {
        const output = path.join(directory, 'model with spaces.plan.json');
        const compiled = path.join(directory, 'compiled.json');
        vi.mocked(runtime.launchRuntime).mockImplementation(async plan => {
            expect(JSON.parse(readFileSync(plan, 'utf8')).bridgeName).toBe('ProductBridge');
        });

        await expect(runAction(source)).resolves.toBeUndefined();
        await compileAction(source, { output: compiled });

        expect(runtime.launchRuntime).toHaveBeenCalledExactlyOnceWith(output, undefined);
        expect(readFileSync(output, 'utf8')).toBe(readFileSync(compiled, 'utf8'));
        expect(readdirSync(directory).sort()).toEqual([
            'compiled.json', 'model with spaces.brg', 'model with spaces.plan.json'
        ]);
    });

    it('writes and retains the plan at the exact nested output path', async () => {
        const output = path.join(directory, 'new plans', 'custom.plan.json');

        await runAction(source, { output });

        expect(runtime.launchRuntime).toHaveBeenCalledExactlyOnceWith(output, undefined);
        expect(JSON.parse(readFileSync(output, 'utf8')).bridgeName).toBe('ProductBridge');
        expect(readdirSync(directory).sort()).toEqual(['model with spaces.brg', 'new plans']);
    });

    it('combines output and runtime selection without changing the relative plan path', async () => {
        const output = path.relative(process.cwd(), path.join(directory, 'plans', 'custom.plan.json'));
        const runtimeJar = path.join(directory, 'selected runtime.jar');

        await runAction(source, { output, runtime: runtimeJar });

        expect(runtime.launchRuntime).toHaveBeenCalledExactlyOnceWith(output, runtimeJar);
        expect(JSON.parse(readFileSync(output, 'utf8')).planVersion).toBe(1);
        expect(readdirSync(directory).sort()).toEqual(['model with spaces.brg', 'plans']);
    });

    it('passes the selected runtime and the default plan path to the launcher', async () => {
        const runtimeJar = path.join(directory, 'selected runtime.jar');
        const output = path.join(directory, 'model with spaces.plan.json');

        await runAction(source, { runtime: runtimeJar });

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

        await expect(runAction(source)).rejects.toThrow();

        expect(runtime.launchRuntime).not.toHaveBeenCalled();
        expect(readdirSync(directory)).toEqual(files);
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
        const failure = new Error('Cannot write plan');
        vi.spyOn(writer, 'writeExecutionPlan').mockImplementation(() => { throw failure; });

        await expect(runAction(source)).rejects.toBe(failure);

        expect(runtime.launchRuntime).not.toHaveBeenCalled();
        expect(readdirSync(directory)).toEqual(['model with spaces.brg']);
        expect(console.log).not.toHaveBeenCalled();
    });

    it('rejects a compiled plan as input without invoking the runtime or modifying the plan', async () => {
        const plan = path.join(directory, 'model with spaces.plan.json');
        await compileAction(source);
        const content = readFileSync(plan, 'utf8');
        vi.mocked(console.log).mockClear();

        await expect(runAction(plan)).rejects.toThrow('Please choose a file with one of these extensions: .brg.');

        expect(runtime.launchRuntime).not.toHaveBeenCalled();
        expect(console.log).not.toHaveBeenCalled();
        expect(readFileSync(plan, 'utf8')).toBe(content);
        expect(readdirSync(directory).sort()).toEqual(['model with spaces.brg', 'model with spaces.plan.json']);
    });

    it.each([
        { failure: new runtime.RuntimeExitError(7), output: undefined },
        { failure: new Error('Could not start Java runtime: ENOENT'), output: undefined },
        { failure: new runtime.RuntimeExitError(7), output: 'plans/custom.plan.json' },
        { failure: new Error('Could not start Java runtime: ENOENT'), output: 'plans/custom.plan.json' }
    ])('preserves the plan and original $failure.message with output=$output', async ({ failure, output }) => {
        const planPath = path.join(directory, output ?? 'model with spaces.plan.json');
        vi.mocked(runtime.launchRuntime).mockRejectedValue(failure);

        await expect(runAction(source, { output: planPath })).rejects.toBe(failure);

        expect(runtime.launchRuntime).toHaveBeenCalledExactlyOnceWith(planPath, undefined);
        expect(JSON.parse(readFileSync(planPath, 'utf8')).planVersion).toBe(1);
        expect(console.log).toHaveBeenCalledExactlyOnceWith(expect.stringContaining('Compiled execution plan:'));
    });
});
