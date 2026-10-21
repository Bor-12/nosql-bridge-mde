import { createHash, randomUUID } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { access, mkdir, open, readFile, rename, rm, stat, writeFile } from 'node:fs/promises';
import { constants } from 'node:fs';
import { homedir } from 'node:os';
import * as path from 'node:path';
import { download, type Download } from './runtime-download.js';
import { expectedRuntime, runtimeAssetUrl } from './runtime-descriptor.js';

export function runtimePath(home = homedir()): string {
    return path.join(home, '.brg', 'runtimes', expectedRuntime.version, expectedRuntime.assetName);
}

export async function readableFile(value: string | undefined, label: string): Promise<string> {
    if (!value?.trim()) throw new Error(`${label} must be a nonblank path.`);
    const resolved = path.resolve(value);
    const info = await stat(resolved).catch(() => { throw new Error(`Cannot access ${label}: ${resolved}.`); });
    if (!info.isFile()) throw new Error(`${label} is not a file: ${resolved}.`);
    await access(resolved, constants.R_OK).catch(() => { throw new Error(`Cannot read ${label}: ${resolved}.`); });
    return resolved;
}

async function digest(file: string): Promise<string> {
    const hash = createHash('sha256');
    for await (const chunk of createReadStream(file)) hash.update(chunk);
    return hash.digest('hex');
}

async function verifyJar(file: string, checksum: string): Promise<boolean> {
    const handle = await open(file, 'r');
    try {
        const header = Buffer.alloc(4);
        await handle.read(header, 0, 4, 0);
        return header.equals(Buffer.from([0x50, 0x4b, 3, 4])) && await digest(file) === checksum;
    } finally { await handle.close(); }
}

export async function runtimeInstalled(home?: string): Promise<boolean> {
    const jar = runtimePath(home);
    try {
        const checksum = (await readFile(`${jar}.sha256`, 'utf8')).trim();
        return /^[a-f0-9]{64}$/.test(checksum) && await verifyJar(jar, checksum);
    } catch { return false; }
}

export async function resolveRuntime(option?: string, env = process.env, home?: string): Promise<string> {
    const explicit = option !== undefined ? option : env.BRG_RUNTIME_JAR;
    if (explicit !== undefined) return readableFile(explicit, 'Runtime JAR');
    if (await runtimeInstalled(home)) return runtimePath(home);
    throw new Error('Bridge Runtime is not installed.\nInstall it with:\n  brg runtime install');
}

export async function installRuntime(home?: string, transfer: Download = download): Promise<string> {
    const jar = runtimePath(home);
    if (await runtimeInstalled(home)) return jar;
    await mkdir(path.dirname(jar), { recursive: true });
    const temporary = `${jar}.${randomUUID()}.tmp`;
    const checksumFile = `${temporary}.sha256`;
    try {
        await transfer(`${runtimeAssetUrl()}.sha256`, checksumFile);
        const checksum = (await readFile(checksumFile, 'utf8')).trim().split(/\s+/)[0];
        if (!/^[a-fA-F0-9]{64}$/.test(checksum)) throw new Error('Invalid runtime SHA-256 asset.');
        await transfer(runtimeAssetUrl(), temporary);
        if (!await verifyJar(temporary, checksum.toLowerCase())) throw new Error('Runtime JAR integrity check failed.');
        await writeFile(checksumFile, checksum.toLowerCase() + '\n');
        await rename(checksumFile, `${jar}.sha256`);
        await rename(temporary, jar);
        return jar;
    } finally {
        await Promise.all([rm(temporary, { force: true }), rm(checksumFile, { force: true })]);
    }
}

export async function runtimeInstallAction(): Promise<void> {
    console.log(`Installing Bridge Runtime ${expectedRuntime.version} (${expectedRuntime.releaseTag})...`);
    console.log(`Runtime ready: ${await installRuntime()}`);
}

export async function runtimeStatusAction(): Promise<void> {
    console.log(`Expected runtime: ${expectedRuntime.version} (${expectedRuntime.releaseTag})`);
    console.log(`Path: ${runtimePath()}`);
    console.log(await runtimeInstalled() ? 'Runtime installed.' : 'Runtime not installed.\nInstall it with:\n  brg runtime install');
}
