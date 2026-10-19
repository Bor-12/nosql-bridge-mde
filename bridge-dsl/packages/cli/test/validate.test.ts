import { spawnSync } from 'node:child_process';
import { copyFileSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import * as path from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

const cli = fileURLToPath(new URL('../bin/cli.js', import.meta.url));
const fixtures = fileURLToPath(new URL('../../../test-inputs/', import.meta.url));
let directory: string;

beforeEach(() => {
    directory = mkdtempSync(path.join(tmpdir(), 'brg-validate-'));
});

afterEach(() => {
    rmSync(directory, { recursive: true, force: true });
});

function run(...args: string[]) {
    const result = spawnSync(process.execPath, [cli, ...args], {
        cwd: directory,
        encoding: 'utf8',
        env: { ...process.env, FORCE_COLOR: '0', NO_COLOR: '1' },
        timeout: 10000
    });
    expect(result.error).toBeUndefined();
    expect(result.signal).toBeNull();
    return result;
}

function copyFixture(name: string) {
    copyFileSync(path.join(fixtures, name), path.join(directory, name));
}

describe('brg validate', () => {
    it('accepts a valid model without modifying it or creating output files', () => {
        copyFixture('minimal.brg');
        const source = readFileSync(path.join(directory, 'minimal.brg'), 'utf8');

        const result = run('validate', 'minimal.brg');

        expect(result.status).toBe(0);
        expect(result.stdout).toContain('Model is valid: minimal.brg');
        expect(result.stderr).toBe('');
        expect(readdirSync(directory)).toEqual(['minimal.brg']);
        expect(readFileSync(path.join(directory, 'minimal.brg'), 'utf8')).toBe(source);
    });

    it('reports the registered semantic diagnostics and fails without creating output', () => {
        copyFixture('default-errors.brg');

        const result = run('validate', 'default-errors.brg');

        expect(result.status).toBe(1);
        expect(result.stdout).toBe('');
        expect(result.stderr).toContain('There are validation errors:');
        expect(result.stderr).toMatch(/line \d+: .*toDecimal/);
        expect(result.stderr).toContain('UnknownTransformation');
        expect(result.stderr.match(/^line \d+:/gm)).toHaveLength(5);
        expect(readdirSync(directory)).toEqual(['default-errors.brg']);
    });

    it('reports syntax diagnostics and fails without creating output', () => {
        const source = readFileSync(path.join(fixtures, 'minimal.brg'), 'utf8');
        writeFileSync(path.join(directory, 'syntax-error.brg'), source.trimEnd().slice(0, -1));

        const result = run('validate', 'syntax-error.brg');

        expect(result.status).toBe(1);
        expect(result.stdout).toBe('');
        expect(result.stderr).toMatch(/line \d+: .*Expecting/);
        expect(readdirSync(directory)).toEqual(['syntax-error.brg']);
    });

    it('rejects a nonexistent file', () => {
        const result = run('validate', 'missing.brg');

        expect(result.status).toBe(1);
        expect(result.stdout).toBe('');
        expect(result.stderr).toContain('File missing.brg does not exist.');
        expect(readdirSync(directory)).toEqual([]);
    });

    it('rejects an unsupported extension', () => {
        copyFileSync(path.join(fixtures, 'minimal.brg'), path.join(directory, 'model.txt'));

        const result = run('validate', 'model.txt');

        expect(result.status).toBe(1);
        expect(result.stdout).toBe('');
        expect(result.stderr).toContain('Please choose a file with one of these extensions: .brg.');
        expect(readdirSync(directory)).toEqual(['model.txt']);
    });
});

describe('brg generate compatibility', () => {
    it('still generates an execution plan from a valid model', () => {
        copyFixture('minimal.brg');

        const result = run('generate', 'minimal.brg', 'plan.json');

        expect(result.status).toBe(0);
        expect(result.stdout).toContain('Code generated succesfully: plan.json');
        expect(result.stderr).toBe('');
        expect(JSON.parse(readFileSync(path.join(directory, 'plan.json'), 'utf8'))).toMatchObject({
            planVersion: 1,
            bridgeName: 'ProductBridge'
        });
    });

    it('still rejects invalid models before generating output', () => {
        copyFixture('default-errors.brg');

        const result = run('generate', 'default-errors.brg', 'plan.json');

        expect(result.status).toBe(1);
        expect(result.stdout).toBe('');
        expect(result.stderr).toContain('There are validation errors:');
        expect(readdirSync(directory)).toEqual(['default-errors.brg']);
    });
});
