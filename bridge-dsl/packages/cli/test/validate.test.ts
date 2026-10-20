import { spawnSync } from 'node:child_process';
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
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

describe('brg compile', () => {
    it.each(['minimal.brg', path.join('models', 'minimal.brg')])('writes the default plan beside %s', source => {
        const sourcePath = path.join(directory, source);
        mkdirSync(path.dirname(sourcePath), { recursive: true });
        copyFileSync(path.join(fixtures, 'minimal.brg'), sourcePath);
        const output = path.join(path.dirname(source), 'minimal.plan.json');

        const result = run('compile', source);

        expect(result.status).toBe(0);
        expect(result.stdout).toContain(`Compiled execution plan: ${output}`);
        expect(result.stderr).toBe('');
        expect(JSON.parse(readFileSync(path.join(directory, output), 'utf8'))).toMatchObject({
            planVersion: 1,
            bridgeName: 'ProductBridge'
        });
        expect(readdirSync(path.dirname(sourcePath)).sort()).toEqual(['minimal.brg', 'minimal.plan.json']);
        expect(readFileSync(sourcePath, 'utf8')).toBe(readFileSync(path.join(fixtures, 'minimal.brg'), 'utf8'));
    });

    it.each(['-o', '--output'])('uses exactly the output path provided with %s', option => {
        copyFixture('minimal.brg');

        const result = run('compile', 'minimal.brg', option, 'plan.json');

        expect(result.status).toBe(0);
        expect(result.stderr).toBe('');
        expect(JSON.parse(readFileSync(path.join(directory, 'plan.json'), 'utf8'))).toMatchObject({
            planVersion: 1,
            bridgeName: 'ProductBridge'
        });
        expect(readdirSync(directory).sort()).toEqual(['minimal.brg', 'plan.json']);
    });

    it.each([
        { options: [] },
        { options: ['-o', path.join('plans', 'invalid.json')] }
    ])('rejects semantic errors before creating any output ($options)', ({ options }) => {
        copyFixture('default-errors.brg');

        const result = run('compile', 'default-errors.brg', ...options);

        expect(result.status).toBe(1);
        expect(result.stdout).toBe('');
        expect(result.stderr).toContain('There are validation errors:');
        expect(result.stderr).toContain("Unknown transformation 'UnknownTransformation'.");
        expect(result.stderr.match(/^line \d+:/gm)).toHaveLength(5);
        expect(readdirSync(directory)).toEqual(['default-errors.brg']);
    });

    it('rejects syntax errors without leaving a partial plan', () => {
        const source = readFileSync(path.join(fixtures, 'minimal.brg'), 'utf8');
        writeFileSync(path.join(directory, 'syntax-error.brg'), source.trimEnd().slice(0, -1));

        const result = run('compile', 'syntax-error.brg');

        expect(result.status).toBe(1);
        expect(result.stdout).toBe('');
        expect(result.stderr).toMatch(/line \d+: .*Expecting/);
        expect(readdirSync(directory)).toEqual(['syntax-error.brg']);
    });
});

describe('CLI public commands', () => {
    it('documents the runtime option and its precedence in run help', () => {
        const result = run('run', '--help');

        expect(result.status).toBe(0);
        expect(result.stdout).toContain('--runtime <file>');
        expect(result.stdout).not.toContain('--keep-plan');
        expect(result.stdout).toContain('-o, --output <file>');
        expect(result.stdout).toMatch(/Java runtime JAR \(takes precedence over\s+BRG_RUNTIME_JAR\)/);
        expect(result.stderr).toBe('');
    });

    it('continues documenting output selection for compile', () => {
        const result = run('compile', '--help');

        expect(result.status).toBe(0);
        expect(result.stdout).toContain('-o, --output <file>');
        expect(result.stdout).not.toContain('--keep-plan');
    });

    it('rejects --keep-plan without creating a plan', () => {
        copyFixture('minimal.brg');

        const result = run('run', 'minimal.brg', '--keep-plan');

        expect(result.status).toBe(1);
        expect(result.stderr).toContain("unknown option '--keep-plan'");
        expect(readdirSync(directory)).toEqual(['minimal.brg']);
    });

    it('exposes validate, compile and run without the legacy generate command', () => {
        const result = run('--help');

        expect(result.status).toBe(0);
        expect(result.stdout).toContain('validate <file>');
        expect(result.stdout).toContain('compile [options] <file>');
        expect(result.stdout).toContain('run [options] <file>');
        expect(result.stdout).not.toContain('generate');
        expect(result.stderr).toBe('');
    });

    it('rejects the removed generate command', () => {
        const result = run('generate', 'minimal.brg', 'plan.json');

        expect(result.status).toBe(1);
        expect(result.stderr).toContain("unknown command 'generate'");
        expect(readdirSync(directory)).toEqual([]);
    });
});
