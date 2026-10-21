import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdtempSync, mkdirSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const cliRoot = fileURLToPath(new URL('../', import.meta.url));
const directory = mkdtempSync(path.join(tmpdir(), 'brg package smoke '));
const npm = process.env.npm_execpath;
assert.ok(npm, 'Run through npm run test:package');
function execute(command, args, cwd, env = process.env) {
    const result = spawnSync(command, args, { cwd, env, encoding: 'utf8', timeout: 120_000,
        windowsVerbatimArguments: process.platform === 'win32' && /cmd\.exe$/i.test(command) });
    assert.ifError(result.error);
    assert.equal(result.status, 0, result.stderr + result.stdout);
    return result.stdout;
}
try {
    const packed = JSON.parse(execute(process.execPath, [npm, 'pack', '--ignore-scripts', '--json', '--pack-destination', directory], cliRoot))[0];
    const contents = packed.files.map(file => file.path).sort();
    assert.deepEqual(contents, ['README.md', 'THIRD_PARTY_NOTICES.txt', 'bin/cli.js', 'dist/cli.js', 'package.json']);
    const prefix = path.join(directory, 'isolated prefix');
    execute(process.execPath, [npm, 'install', '-g', '--prefix', prefix, '--offline', '--ignore-scripts', '--no-audit', '--no-fund', path.join(directory, packed.filename)], directory);
    const packageName = JSON.parse(readFileSync(path.join(cliRoot, 'package.json'), 'utf8')).name;
    const installed = path.join(prefix, process.platform === 'win32' ? '' : 'lib', 'node_modules', packageName);
    const metadata = JSON.parse(readFileSync(path.join(installed, 'package.json'), 'utf8'));
    assert.deepEqual(metadata.dependencies, {});
    const consumer = path.join(directory, 'independent consumer');
    mkdirSync(consumer);
    copyFileSync(fileURLToPath(new URL('../../../test-inputs/minimal.brg', import.meta.url)), path.join(consumer, 'migration.brg'));
    const env = { ...process.env, PATH: '', BRG_RUNTIME_JAR: '', HOME: directory, USERPROFILE: directory, NO_COLOR: '1' };
    const invoke = (...args) => execute(process.execPath, [path.join(installed, 'bin', 'cli.js'), ...args], consumer, env);
    assert.match(invoke('--help'), /runtime/);
    assert.equal(invoke('--version').trim(), metadata.version);
    assert.match(invoke('validate', 'migration.brg'), /Model is valid/);
    assert.match(invoke('compile', 'migration.brg'), /Compiled execution plan/);
    assert.equal(JSON.parse(readFileSync(path.join(consumer, 'migration.plan.json'), 'utf8')).planVersion, 1);
    assert.ok(!existsSync(path.join(directory, '.brg')));
    if (process.platform === 'win32') {
        const shim = path.join(prefix, 'brg.cmd');
        assert.equal(execute(process.env.ComSpec || 'cmd.exe', ['/d', '/s', '/c', `""${shim}" --version"`], consumer).trim(), metadata.version);
    } else {
        assert.equal(execute(path.join(prefix, 'bin', 'brg'), ['--version'], consumer).trim(), metadata.version);
    }
    console.log(JSON.stringify({ contents, bytes: packed.size, unpackedBytes: packed.unpackedSize, installation: 'isolated global prefix', checks: ['help', 'version', 'validate', 'compile', 'brg shim'], runtimeRequired: false }, null, 2));
} finally {
    rmSync(directory, { recursive: true, force: true });
}
