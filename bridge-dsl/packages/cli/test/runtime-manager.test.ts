import { createHash } from 'node:crypto';
import { mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import * as path from 'node:path';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { installRuntime, resolveRuntime, runtimeInstalled, runtimePath, runtimeStatusAction } from '../src/runtime/runtime-manager.js';
import { expectedRuntime, runtimeAssetUrl } from '../src/runtime/runtime-descriptor.js';
import { download } from '../src/runtime/runtime-download.js';

let home: string;
// Minimal ZIP signature plus payload: transfer integrity is checked against release SHA-256.
const artifact = Buffer.from('504b03046272696467652d72756e74696d65', 'hex');
const checksum = createHash('sha256').update(artifact).digest('hex');
const transfer = vi.fn(async (url: string, file: string) => {
    await writeFile(file, url.endsWith('.sha256') ? `${checksum}  bridge-runtime.jar\n` : artifact);
});

beforeEach(async () => { home = await mkdtemp(path.join(tmpdir(), 'brg home with spaces ')); transfer.mockClear(); });
afterEach(async () => { vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); await rm(home, { recursive: true, force: true }); });

it('uses a fixed release and a home path independent of cwd', () => {
    expect(runtimePath(home)).toBe(path.join(home, '.brg', 'runtimes', expectedRuntime.version, 'bridge-runtime.jar'));
    expect(runtimeAssetUrl()).toBe('https://github.com/Bor-12/nosql-bridge-mde/releases/download/runtime-v1.0.0/bridge-runtime.jar');
});

it('creates directories, stages downloads, installs verified bytes and skips an existing valid installation', async () => {
    expect(await runtimeInstalled(home)).toBe(false);
    expect(await installRuntime(home, transfer)).toBe(runtimePath(home));
    expect(await runtimeInstalled(home)).toBe(true);
    expect(await readFile(runtimePath(home))).toEqual(artifact);
    expect(transfer.mock.calls.every(([, file]) => file.includes('.tmp'))).toBe(true);
    expect(await readdir(path.dirname(runtimePath(home)))).toEqual(['bridge-runtime.jar', 'bridge-runtime.jar.sha256']);
    await installRuntime(home, transfer);
    expect(transfer).toHaveBeenCalledTimes(2);
});

it.each(['HTTP', 'interrupted', 'checksum', 'not a JAR'])('cleans up failed %s downloads and allows retry', async kind => {
    const broken = async (url: string, file: string) => {
        if (kind === 'HTTP') throw new Error('HTTP 404');
        await transfer(url, file);
        if (!url.endsWith('.sha256')) {
            if (kind === 'interrupted') throw new Error('connection reset');
            await writeFile(file, kind === 'checksum' ? Buffer.from('PK\x03\x04wrong') : 'HTML error page');
        }
    };
    await expect(installRuntime(home, broken)).rejects.toThrow();
    expect(await runtimeInstalled(home)).toBe(false);
    expect(await readdir(path.dirname(runtimePath(home)))).toEqual([]);
    await installRuntime(home, transfer);
    expect(await runtimeInstalled(home)).toBe(true);
});

it('detects and replaces a damaged cached JAR', async () => {
    await installRuntime(home, transfer);
    await writeFile(runtimePath(home), 'corrupt');
    expect(await runtimeInstalled(home)).toBe(false);
    await installRuntime(home, transfer);
    expect(await runtimeInstalled(home)).toBe(true);
});

it('resolves option, then environment, then managed runtime and rejects invalid overrides', async () => {
    await installRuntime(home, transfer);
    const explicit = path.join(home, 'explicit.jar');
    const environment = path.join(home, 'env.jar');
    await writeFile(explicit, artifact); await writeFile(environment, artifact);
    expect(await resolveRuntime(explicit, { BRG_RUNTIME_JAR: environment }, home)).toBe(explicit);
    expect(await resolveRuntime(undefined, { BRG_RUNTIME_JAR: environment }, home)).toBe(environment);
    expect(await resolveRuntime(undefined, {}, home)).toBe(runtimePath(home));
    for (const value of ['', ' ', path.join(home, 'missing.jar'), home]) {
        await expect(resolveRuntime(value, { BRG_RUNTIME_JAR: environment }, home)).rejects.toThrow();
        await expect(resolveRuntime(undefined, { BRG_RUNTIME_JAR: value }, home)).rejects.toThrow();
    }
});

it('reports absence clearly', async () => {
    await expect(resolveRuntime(undefined, {}, home)).rejects.toThrow('brg runtime install');
});

it('reports expected version, path and absent/installed status', async () => {
    // Node homedir honours this platform-specific environment variable.
    vi.stubEnv(process.platform === 'win32' ? 'USERPROFILE' : 'HOME', home);
    expect(runtimePath()).toBe(runtimePath(home));
    const log = vi.spyOn(console, 'log').mockImplementation(() => {});
    await runtimeStatusAction();
    expect(log).toHaveBeenCalledWith(expect.stringContaining(expectedRuntime.version));
    expect(log).toHaveBeenCalledWith(`Path: ${runtimePath(home)}`);
    expect(log).toHaveBeenCalledWith(expect.stringContaining('Runtime not installed.'));
    await installRuntime(home, transfer);
    await runtimeStatusAction();
    expect(log).toHaveBeenCalledWith('Runtime installed.');
});

it('downloads HTTPS with redirects into a new temporary file', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(new Response(null, { status: 302, headers: { location: 'https://assets.example/runtime' } }))
        .mockResolvedValueOnce(new Response(artifact));
    vi.stubGlobal('fetch', fetch);
    const file = path.join(home, 'download.tmp');
    await download('https://example.org/runtime', file);
    expect(await readFile(file)).toEqual(artifact);
    expect(fetch).toHaveBeenCalledTimes(2);
});

it.each(['http://example.org/file', 'https://example.org/insecure-redirect', 'https://example.org/404'])('rejects insecure transport or HTTP failure: %s', async url => {
    const fetch = vi.fn().mockResolvedValue(url.endsWith('404') ? new Response(null, { status: 404 })
        : new Response(null, { status: 302, headers: { location: 'http://example.org/file' } }));
    vi.stubGlobal('fetch', fetch);
    await expect(download(url, path.join(home, 'fail.tmp'))).rejects.toThrow();
    expect(await readdir(home)).toEqual([]);
});

it('cleans staging files when the HTTP response stream is interrupted', async () => {
    const fetch = vi.fn().mockImplementation(async (url: string) => url.endsWith('.sha256')
        ? new Response(checksum)
        : new Response(new ReadableStream({ start(controller) { controller.enqueue(artifact); controller.error(new Error('network interrupted')); } })));
    vi.stubGlobal('fetch', fetch);
    await expect(installRuntime(home)).rejects.toThrow('network interrupted');
    expect(await readdir(path.dirname(runtimePath(home)))).toEqual([]);
});

it('rejects redirects without a location and redirect loops', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(null, { status: 302 }));
    vi.stubGlobal('fetch', fetch);
    await expect(download('https://example.org/file', path.join(home, 'fail.tmp'))).rejects.toThrow('no location');
    fetch.mockImplementation(async () => new Response(null, { status: 302, headers: { location: 'https://example.org/loop' } }));
    await expect(download('https://example.org/file', path.join(home, 'fail.tmp'))).rejects.toThrow('Too many');
    expect(await readdir(home)).toEqual([]);
});
