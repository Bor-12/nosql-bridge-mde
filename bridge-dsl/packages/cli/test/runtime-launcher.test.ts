import { spawn, type ChildProcess } from 'node:child_process';
import { EventEmitter } from 'node:events';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import * as path from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { launchRuntime, RuntimeExitError } from '../src/runtime-launcher.js';

vi.mock('node:child_process', () => ({ spawn: vi.fn() }));

let directory: string;
let runtimeJar: string;
let child: ChildProcess;

beforeEach(() => {
    directory = mkdtempSync(path.join(tmpdir(), 'brg-launcher-'));
    runtimeJar = path.join(directory, 'runtime with spaces.jar');
    writeFileSync(runtimeJar, 'test artifact');
    vi.stubEnv('BRG_RUNTIME_JAR', runtimeJar);
    child = new EventEmitter() as ChildProcess;
    vi.mocked(spawn).mockReturnValue(child);
});

afterEach(() => {
    vi.resetAllMocks();
    vi.unstubAllEnvs();
    rmSync(directory, { recursive: true, force: true });
});

function finishWith(code: number | null, signal: NodeJS.Signals | null = null) {
    vi.mocked(spawn).mockImplementation(() => {
        queueMicrotask(() => child.emit('close', code, signal));
        return child;
    });
}

describe('Java runtime launcher', () => {
    it('uses the public argument contract without a shell, inherits output and waits for completion', async () => {
        const plan = path.join('plans with spaces', 'model & data.plan.json');
        vi.stubEnv('BRG_RUNTIME_JAR', path.relative(process.cwd(), runtimeJar));
        let completed = false;

        const running = launchRuntime(plan).then(() => { completed = true; });
        await vi.waitFor(() => expect(spawn).toHaveBeenCalledExactlyOnceWith(
            'java', ['-jar', runtimeJar, '--plan', plan], { stdio: 'inherit', shell: false }
        ));
        expect(completed).toBe(false);
        child.emit('close', 0, null);
        await running;

        expect(completed).toBe(true);
    });

    it.each([undefined, '', '   '])('rejects missing or blank runtime configuration (%s)', async value => {
        vi.stubEnv('BRG_RUNTIME_JAR', value);

        await expect(launchRuntime('model.plan.json')).rejects.toThrow('--runtime <file> or set BRG_RUNTIME_JAR');

        expect(spawn).not.toHaveBeenCalled();
    });

    it.each([undefined, 'missing', 'other'])('selects the explicit JAR over environment configuration (%s)', async environment => {
        if (environment === 'other') {
            const otherJar = path.join(directory, 'other.jar');
            writeFileSync(otherJar, 'other artifact');
            vi.stubEnv('BRG_RUNTIME_JAR', otherJar);
        } else {
            vi.stubEnv('BRG_RUNTIME_JAR', environment);
        }
        finishWith(0);

        await expect(launchRuntime('model.plan.json', runtimeJar)).resolves.toBeUndefined();

        expect(spawn).toHaveBeenCalledExactlyOnceWith(
            'java', ['-jar', runtimeJar, '--plan', 'model.plan.json'], { stdio: 'inherit', shell: false }
        );
    });

    it.each(['', '   '])('rejects a blank explicit runtime without falling back to the environment (%s)', async value => {
        await expect(launchRuntime('model.plan.json', value)).rejects.toThrow('--runtime <file> or set BRG_RUNTIME_JAR');

        expect(spawn).not.toHaveBeenCalled();
    });

    it('rejects a nonexistent explicit JAR without falling back to a valid environment JAR', async () => {
        const missingJar = path.join(directory, 'missing.jar');

        await expect(launchRuntime('model.plan.json', missingJar)).rejects.toThrow(`Cannot access runtime JAR: ${missingJar}`);

        expect(spawn).not.toHaveBeenCalled();
    });

    it('rejects a nonexistent JAR before starting Java', async () => {
        vi.stubEnv('BRG_RUNTIME_JAR', path.join(directory, 'missing.jar'));

        await expect(launchRuntime('model.plan.json')).rejects.toThrow('Cannot access runtime JAR:');

        expect(spawn).not.toHaveBeenCalled();
    });

    it('rejects a directory configured as the JAR', async () => {
        vi.stubEnv('BRG_RUNTIME_JAR', directory);

        await expect(launchRuntime('model.plan.json')).rejects.toThrow('Runtime JAR is not a file:');

        expect(spawn).not.toHaveBeenCalled();
    });

    it('reports a missing Java executable', async () => {
        vi.mocked(spawn).mockImplementation(() => {
            queueMicrotask(() => child.emit('error', new Error('spawn java ENOENT')));
            return child;
        });

        await expect(launchRuntime('model.plan.json')).rejects.toThrow(
            'Could not start Java runtime: spawn java ENOENT. Check that Java 21 is available on PATH.'
        );
    });

    it('preserves a nonzero exit code', async () => {
        finishWith(7);

        await expect(launchRuntime('model.plan.json')).rejects.toMatchObject({
            constructor: RuntimeExitError, exitCode: 7, message: 'Java runtime exited with code 7.'
        });
    });

    it('reports termination by signal', async () => {
        finishWith(null, 'SIGTERM');

        await expect(launchRuntime('model.plan.json')).rejects.toThrow('Java runtime terminated by signal SIGTERM.');
    });

    it('reports termination without an exit code', async () => {
        finishWith(null);

        await expect(launchRuntime('model.plan.json')).rejects.toThrow('Java runtime exited without an exit code.');
    });
});
