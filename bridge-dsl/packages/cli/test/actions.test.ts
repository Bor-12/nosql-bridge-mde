import type { BridgeModel } from 'bridge-language';
import { createBridgeServices, createExecutionPlan } from 'bridge-language';
import { clearDocuments } from 'langium/test';
import { NodeFileSystem } from 'langium/node';
import { copyFileSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import * as path from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { compileAction, validateAction } from '../src/actions.js';


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
    it('rejects infrastructure credentials and endpoints in the DSL', async () => {
        const source = copyFixture();
        writeFileSync(source, readFileSync(source, 'utf8').replace('database "shop"', 'database "shop" uri "mongodb://example:27017"'));
        await expect(validateAction(source)).rejects.toThrow("Unknown property 'uri' for MongoDB source.");
    });
    it('compiles the same plan as the Java work-message compatibility fixture', async () => {
        const fixture = JSON.parse(readFileSync(fileURLToPath(new URL('../../../../bridge-runtime/bridge-common/src/test/resources/contracts/work-unit-v1.json', import.meta.url)), 'utf8'));
        const source = copyFixture();
        const model = await extractAstNode<BridgeModel>(source, services.Bridge);
        expect(createExecutionPlan(model)).toEqual(fixture.plan);
    });
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
