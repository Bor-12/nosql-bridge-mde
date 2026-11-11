import { spawn } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { chmod, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { homedir, tmpdir } from 'node:os';
import * as path from 'node:path';
import { randomUUID } from 'node:crypto';
import { createInterface } from 'node:readline';
import { setTimeout as delay } from 'node:timers/promises';
import { stringify } from 'yaml';
import type { RunConfig } from './config.js';
import { parseEvent, RunProgress, type RuntimeEvent } from './events.js';
import { runtimeImages } from './images.js';

// Make standard shared profiles available inside the container without parsing
// credentials or depending on AWS CLI. Java's default provider chain selects them.
export async function prepareAwsCredentials(temporary: string, env = process.env, home = homedir()) {
    const environment: Record<string, string> = { AWS_EC2_METADATA_DISABLED: 'true' };
    const mounts: { source: string; target: string }[] = [];
    for (const key of ['AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'AWS_SESSION_TOKEN', 'AWS_PROFILE']) {
        const value = env[key];
        if (value) {
            if (/[\r\n\0]/.test(value)) throw new Error(`Invalid environment variable: ${key}`);
            environment[key] = value;
        }
    }
    if (environment.AWS_PROFILE && !(environment.AWS_ACCESS_KEY_ID && environment.AWS_SECRET_ACCESS_KEY)) {
        for (const [key, defaultName, localName] of [
            ['AWS_CONFIG_FILE', 'config', 'aws-config'],
            ['AWS_SHARED_CREDENTIALS_FILE', 'credentials', 'aws-credentials']
        ]) {
            let content: string;
            try { content = await readFile(env[key] ?? path.join(home, '.aws', defaultName), 'utf8'); }
            catch (error) {
                if (!env[key] && (error as NodeJS.ErrnoException).code === 'ENOENT') continue;
                throw new Error(`Cannot read AWS profile file (${key}).`);
            }
            const source = path.join(temporary, localName);
            const target = `/run/bridge/${localName}`;
            await writeFile(source, content, { mode: 0o600 });
            await chmod(source, 0o444);
            mounts.push({ source, target });
            environment[key] = target;
        }
        if (!mounts.length) throw new Error('AWS_PROFILE requires readable shared config or credentials files. Alternatively export temporary AWS credentials.');
    }
    return { environment, mounts };
}

export interface LogFollower { close(): Promise<void> }
export interface DockerClient {
    command(args: string[], signal?: AbortSignal): Promise<string>;
    follow(name: string, destination: string, event: (value: RuntimeEvent) => void, failed: (error: Error) => void): LogFollower;
}

export class Docker implements DockerClient {
    async command(args: string[], signal?: AbortSignal): Promise<string> {
        return new Promise((resolve, reject) => {
            const child = spawn('docker', args, { shell: false, windowsHide: true, signal, stdio: ['ignore', 'pipe', 'pipe'] });
            let stdout = '';
            let stderr = '';
            let bytes = 0;
            child.stdout.on('data', chunk => { stdout += String(chunk); bytes += chunk.length; if (bytes > 16_000_000) child.kill(); });
            // Runtime diagnostics are collected separately. Do not echo arguments or credentials.
            child.stderr.on('data', chunk => { stderr = (stderr + String(chunk)).slice(-12000); });
            child.on('error', error => reject(new Error(signal?.aborted
                ? (signal.reason?.name === 'TimeoutError' ? 'Execution timed out.' : 'Execution cancelled.')
                : (error as NodeJS.ErrnoException).code === 'ENOENT'
                    ? 'Docker executable not found. Install Docker and make it available on PATH.'
                    : 'Cannot run Docker. Check installation and permissions.')));
            child.on('close', code => code === 0 && bytes <= 16_000_000 ? resolve(stdout.trim()) : reject(new Error(args[0] === 'info'
                ? 'Docker daemon is unavailable or inaccessible. Start Docker Desktop or the Docker daemon and check permissions.'
                : `Docker ${args[0]} failed (exit ${code ?? 'unknown'}). Check engine, images, network and the run logs.${args[0] === 'pull' ? '\n' + stderr : ''}`)));
        });
    }

    follow(name: string, destination: string, event: (value: RuntimeEvent) => void, failed: (error: Error) => void): LogFollower {
        const child = spawn('docker', ['logs', '--follow', name], { shell: false, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
        const output = createWriteStream(destination, { mode: 0o600 });
        const lines = createInterface({ input: child.stdout });
        output.on('error', () => failed(new Error(`Cannot persist runtime logs at ${destination}.`)));
        lines.on('line', line => {
            output.write(line + '\n');
            try { const value = parseEvent(line); if (value) event(value); }
            catch (error) { failed(error instanceof Error ? error : new Error('Invalid event.')); }
        });
        child.stderr.on('data', chunk => output.write(chunk));
        child.on('error', () => failed(new Error('Cannot follow container events.')));
        let stopping = false;
        const finished = new Promise<void>(resolve => child.on('close', code => {
            if (!stopping && code !== 0) failed(new Error('Runtime log stream failed; completion cannot be verified.'));
            lines.close(); output.end(resolve);
        }));
        return { async close() { stopping = true; child.kill(); await finished; } };
    }
}

interface ContainerState { Running: boolean; ExitCode: number; OOMKilled: boolean }
interface Manifest { version: 1; id: string; containers: string[]; state: string; migrationId?: string; acknowledged?: number; published?: number; temporary?: string; logsDirectory?: string }

export interface RuntimeImages {
    orchestrator: string;
    worker: string;
}

export async function runDocker(plan: string, config: RunConfig, options: {
    docker?: DockerClient; runsRoot?: string; signal?: AbortSignal; tickMs?: number; logsDir?: string; images?: RuntimeImages;
} = {}): Promise<void> {
    const docker = options.docker ?? new Docker();
    const images = options.images ?? runtimeImages;
    const id = `brg-${randomUUID()}`;
    const directory = path.join(options.runsRoot ?? path.join(homedir(), '.brg', 'runs'), id);
    const logsDirectory = options.logsDir === undefined ? directory : path.join(path.resolve(options.logsDir), id);
    await mkdir(directory, { recursive: true, mode: 0o700 });
    const manifest: Manifest = { version: 1, id, containers: [], state: 'STARTED' };
    if (options.logsDir !== undefined) manifest.logsDirectory = logsDirectory;
    const save = () => writeFile(path.join(directory, 'run.json'), JSON.stringify(manifest, null, 2) + '\n', { mode: 0o600 });
    const progress = new RunProgress();
    const followers: LogFollower[] = [];
    const ready = new Set<string>();
    const workers: string[] = [];
    let streamFailure: Error | undefined;
    let temporary: string | undefined;
    let credentialMounts: { source: string; target: string }[] = [];
    let failure: unknown;
    let cleanupFailed = false;
    const started = Date.now();
    const check = () => {
        if (options.signal?.aborted) throw new Error(options.signal.reason?.name === 'TimeoutError'
            ? 'Execution timed out; destination may contain partial writes.'
            : 'Execution cancelled; destination may contain partial writes.');
        if (streamFailure) throw streamFailure;
        if (progress.state === 'FAILED') throw new Error('Runtime reported a definitive failure. See run logs; partial writes are possible.');
        if (Date.now() - started > config.timeoutSeconds * 1000) throw new Error('Execution timed out; completion is unverified. Check connections, credentials, queue consumers and run logs.');
    };
    const inspect = async (name: string): Promise<ContainerState> => JSON.parse(await docker.command(['inspect', '--format', '{{json .State}}', name], options.signal));
    const start = async (name: string, image: string, role: 'worker' | 'orchestrator') => {
        manifest.containers.push(name);
        await save(); // Record ownership before creating anything, including interrupted docker run calls.
        const args = ['run', '--detach', '--name', name, '--label', `dev.nosqlbridge.run=${id}`,
            '--add-host', 'host.docker.internal:host-gateway', '--env-file', path.join(temporary!, 'runtime.env'),
            '--mount', `type=bind,source=${path.join(temporary!, 'runtime.yaml')},target=/run/bridge/config.yaml,readonly`];
        if (config.network) args.push('--network', config.network);
        for (const mount of credentialMounts) args.push('--mount', `type=bind,source=${mount.source},target=${mount.target},readonly`);
        if (role === 'orchestrator') args.push('--mount', `type=bind,source=${path.join(temporary!, 'plan.json')},target=/run/bridge/plan.json,readonly`);
        args.push(image, '--config', '/run/bridge/config.yaml');
        if (role === 'orchestrator') args.push('--plan', '/run/bridge/plan.json');
        await docker.command(args, options.signal);
        followers.push(docker.follow(name, path.join(logsDirectory, `${role}-${name}.log`), event => {
            if (event.type === 'READY' && role === 'worker') ready.add(name);
            progress.accept(event, role);
        }, error => { streamFailure = error; }));
    };
    try {
        await save();
        try {
            await mkdir(logsDirectory, { recursive: true, mode: 0o700 });
            // Prepare every log before starting any container, checking actual write access.
            const files = [
                ...Array.from({ length: config.workers.count }, (_, index) => `worker-${id}-worker-${index}.log`),
                `orchestrator-${id}-orchestrator.log`
            ];
            for (const file of files) await writeFile(path.join(logsDirectory, file), '', { flag: 'wx', mode: 0o600 });
        } catch (error) {
            throw new Error(`Cannot prepare runtime logs directory ${logsDirectory} (${(error as NodeJS.ErrnoException).code ?? 'write failed'}). Check the path and write permissions.`);
        }
        console.log(`STARTED ${id}; logs: ${logsDirectory}`);
        try { await docker.command(['info', '--format', '{{.ServerVersion}}'], options.signal); }
        catch (error) {
            check();
            throw new Error(`Docker is not available. Check that Docker is installed, running and accessible. ${error instanceof Error ? error.message : 'Docker info failed.'}`);
        }
        const ensureImage = async (role: 'worker' | 'orchestrator') => {
            const image = images[role];
            try { await docker.command(['image', 'inspect', image], options.signal); }
            catch {
                check();
                if (options.images) throw new Error(`Injected runtime image ${image} is missing locally. Injected images are never downloaded.`);
                console.log(`Downloading ${role} runtime ${runtimeImages.version}: ${image}`);
                try { await docker.command(['pull', image], options.signal); }
                catch (error) {
                    check();
                    throw new Error(`Cannot download official runtime image ${image}. ${error instanceof Error ? error.message : 'Docker pull failed.'}`);
                }
            }
            check();
            return image;
        };
        const orchestratorImage = await ensureImage('orchestrator');
        const workerImage = await ensureImage('worker');
        temporary = await mkdtemp(path.join(tmpdir(), 'brg-run-'));
        manifest.temporary = temporary;
        await save();
        await chmod(temporary, 0o700);
        await writeFile(path.join(temporary, 'runtime.yaml'), stringify(config.runtime), { mode: 0o600 });
        // The private parent directory protects host access; the bind-mounted file
        // must also be readable by the image's non-root UID inside the container.
        await chmod(path.join(temporary, 'runtime.yaml'), 0o444);
        await writeFile(path.join(temporary, 'plan.json'), await readFile(plan), { mode: 0o600 });
        await chmod(path.join(temporary, 'plan.json'), 0o444);
        const { environment, mounts } = await prepareAwsCredentials(temporary);
        credentialMounts = mounts;
        await writeFile(path.join(temporary, 'runtime.env'), Object.entries(environment).map(([key, value]) => `${key}=${value}`).join('\n') + '\n', { mode: 0o600 });
        for (let index = 0; index < config.workers.count; index++) {
            check();
            const name = `${id}-worker-${index}`;
            workers.push(name);
            await start(name, workerImage, 'worker');
        }
        while (ready.size < workers.length) {
            check();
            for (const name of workers) if (!(await inspect(name)).Running) throw new Error('Worker stopped during startup. See run logs.');
            await delay(options.tickMs ?? 1000);
        }
        const orchestrator = `${id}-orchestrator`;
        await start(orchestrator, orchestratorImage, 'orchestrator');
        let publisherExited = false;
        let lastState = 'STARTED';
        let publishedReported = false;
        while (true) {
            check();
            const state = await inspect(orchestrator);
            publisherExited = !state.Running;
            if (publisherExited && (state.ExitCode !== 0 || state.OOMKilled)) throw new Error('Orchestrator failed. See run logs; some WorkUnits may already have been published.');
            for (const name of workers) {
                const worker = await inspect(name);
                if (!worker.Running || worker.OOMKilled) throw new Error('Worker stopped unexpectedly; completion is unverified. See run logs.');
            }
            manifest.migrationId = progress.migrationId;
            manifest.published = progress.expected;
            manifest.acknowledged = progress.completedUnits;
            if (!publishedReported && progress.expected !== undefined) {
                console.log(`PUBLISHED ${progress.migrationId} (${progress.expected} WorkUnits; processing not yet verified)`);
                lastState = 'PUBLISHED';
                publishedReported = true;
            }
            const observedState = progress.state === 'COMPLETED' ? 'PROCESSING' : progress.state;
            manifest.state = observedState;
            if (lastState !== observedState) { console.log(`${observedState} ${progress.migrationId ?? ''} (${progress.completedUnits}/${progress.expected ?? '?'} WorkUnits)`); lastState = observedState; }
            await save();
            if (publisherExited && progress.expected !== undefined) {
                if (progress.state === 'COMPLETED') { manifest.state = 'COMPLETED'; break; }
            }
            await delay(options.tickMs ?? 1000);
        }
    } catch (error) {
        failure = error;
        manifest.state = 'FAILED';
    } finally {
        // Never stop, purge or delete the user's queue or databases.
        let owned: string[] = [];
        try {
            const names = await docker.command(['ps', '--all', '--filter', `label=dev.nosqlbridge.run=${id}`, '--format', '{{.Names}}']);
            owned = names.split(/\r?\n/).filter(name => manifest.containers.includes(name));
        } catch { cleanupFailed = true; }
        for (const name of owned.slice().reverse()) {
            try { await docker.command(['stop', '--time', '30', name]); }
            catch { /* A failed create may have left no container; rm below checks ownership/existence. */ }
        }
        for (const follower of followers) {
            try { await follower.close(); } catch { cleanupFailed = true; }
        }
        for (const name of owned.slice().reverse()) {
            try {
                const owner = await docker.command(['inspect', '--format', '{{index .Config.Labels "dev.nosqlbridge.run"}}', name]);
                if (owner !== id) throw new Error('Container ownership mismatch.');
                await docker.command(['rm', name]);
            } catch { cleanupFailed = true; }
        }
        if (temporary) {
            try {
                for (const file of ['runtime.yaml', 'plan.json', 'aws-config', 'aws-credentials']) await chmod(path.join(temporary, file), 0o600).catch(() => {});
                await rm(temporary, { recursive: true, force: true });
                delete manifest.temporary;
            } catch { cleanupFailed = true; }
        }
        if (streamFailure && !failure) failure = streamFailure;
        if (progress.state === 'FAILED' && !failure) failure = new Error('Runtime reported a failure during shutdown. See run logs.');
        if (cleanupFailed && !failure) failure = new Error(`Cleanup incomplete. Use brg cleanup ${id}.`);
        if (failure) manifest.state = 'FAILED';
        await save();
    }
    if (failure) { console.error(`FAILED ${id}; see ${logsDirectory}`); throw failure; }
    if (manifest.state === 'COMPLETED') console.log(`COMPLETED ${progress.migrationId} (${progress.completedUnits}/${progress.expected} WorkUnits acknowledged)`);
}

export async function cleanupRun(id: string, runsRoot = path.join(homedir(), '.brg', 'runs'), docker: DockerClient = new Docker()): Promise<void> {
    if (!/^brg-[0-9a-f-]{36}$/.test(id)) throw new Error('Invalid execution id.');
    const manifest: Manifest = JSON.parse(await readFile(path.join(runsRoot, id, 'run.json'), 'utf8'));
    if (manifest.version !== 1 || manifest.id !== id || !Array.isArray(manifest.containers)) throw new Error('Invalid run manifest.');
    // Failure to reach Docker is an error, not evidence that cleanup succeeded.
    const existing = (await docker.command(['ps', '--all', '--filter', `label=dev.nosqlbridge.run=${id}`, '--format', '{{.Names}}'])).split(/\r?\n/);
    for (const name of manifest.containers) {
        if (!new RegExp(`^${id}-(worker-\\d+|orchestrator)$`).test(name)) throw new Error('Invalid container identity.');
        if (!existing.includes(name)) continue;
        const owner = await docker.command(['inspect', '--format', '{{index .Config.Labels "dev.nosqlbridge.run"}}', name]);
        if (owner !== id) throw new Error('Container ownership mismatch; nothing removed.');
        await docker.command(['stop', '--time', '30', name]);
        await docker.command(['rm', name]);
    }
    if (manifest.temporary) {
        const temporary = path.resolve(manifest.temporary);
        if (path.dirname(temporary) !== path.resolve(tmpdir()) || !path.basename(temporary).startsWith('brg-run-')) throw new Error('Invalid temporary directory; not removed.');
        for (const file of ['runtime.yaml', 'plan.json', 'aws-config', 'aws-credentials']) await chmod(path.join(temporary, file), 0o600).catch(() => {});
        await rm(temporary, { recursive: true, force: true });
        delete manifest.temporary;
        await writeFile(path.join(runsRoot, id, 'run.json'), JSON.stringify(manifest, null, 2) + '\n', { mode: 0o600 });
    }
    console.log(`Cleanup completed for ${id}. External services and data were preserved.`);
}
