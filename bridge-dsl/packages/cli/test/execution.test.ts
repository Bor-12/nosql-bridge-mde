import { mkdir, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import * as path from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { parseRunConfig } from '../src/execution/config.js';
import { parseEvent, RunProgress, type RuntimeEvent } from '../src/execution/events.js';
import { cleanupRun, prepareAwsCredentials, runDocker, type DockerClient } from '../src/execution/docker.js';
import { runAction } from '../src/actions.js';
import * as dockerModule from '../src/execution/docker.js';
import { runtimeImages } from '../src/execution/images.js';

const yaml = `connections:
  mongodb:
    uri: \${MONGO_URI}
  dynamodb:
    endpoint: http://host.docker.internal:8000
    region: us-east-1
  sqs:
    endpoint: http://host.docker.internal:9324
    queueUrl: http://host.docker.internal:9324/000000000000/jobs
    region: us-east-1
`;
const env = { MONGO_URI: 'mongodb://host.docker.internal:27017' };
const migrationId = '550e8400-e29b-41d4-a716-446655440000';
const injectedImages = { worker: 'e2e-worker:candidate', orchestrator: 'e2e-orchestrator:candidate' };
const event = (type: RuntimeEvent['type'], fields = {}): RuntimeEvent => ({ eventVersion: 1, type, migrationId, ...fields });
let directory: string;
beforeEach(async () => {
    directory = await mkdtemp(path.join(tmpdir(), 'brg-execution-'));
    vi.stubEnv('AWS_PROFILE', undefined);
    vi.stubEnv('BRG_TEST_WORKER_IMAGE', undefined);
    vi.stubEnv('BRG_TEST_ORCHESTRATOR_IMAGE', undefined);
});
afterEach(async () => { vi.restoreAllMocks(); vi.unstubAllEnvs(); await rm(directory, { recursive: true, force: true }); });

describe('external configuration', () => {
    it('uses AWS region resolution without endpoint overrides while execution remains Docker', () => {
        const source = yaml.replace(/    endpoint:.*\n/g, '').replaceAll('us-east-1', 'eu-west-1').replace('http://host.docker.internal:9324/000000000000/jobs', 'https://sqs.eu-west-1.amazonaws.com/123456789012/jobs');
        const config = parseRunConfig(source, env);
        expect(config.provider).toBe('docker');
        const bridge = config.runtime.bridge as any;
        expect(bridge.dynamodb).toEqual({ region: 'eu-west-1' });
        expect(bridge.sqs.endpoint).toBeUndefined();
        expect(bridge.sqs['queue-url']).toContain('amazonaws.com');
    });
    it('accepts remote services independently of the Docker execution location', () => {
        const config = parseRunConfig(yaml.replaceAll('host.docker.internal', 'services.example.org'), { MONGO_URI: 'mongodb://mongo.example.org:27017' });
        expect(config.provider).toBe('docker');
        expect(JSON.stringify(config.runtime)).toContain('mongo.example.org');
        expect(JSON.stringify(config.runtime)).toContain('services.example.org');
    });
    it.each(['mongodb://mongo1.example:27017,mongo2.example:27017/shop?replicaSet=rs0', 'mongodb+srv://user:password@cluster.example/shop'])('accepts MongoDB URI %s', uri => {
        expect((parseRunConfig(yaml, { MONGO_URI: uri }).runtime.bridge as any).mongodb.uri).toBe(uri);
    });
    it('detects loopback even in a MongoDB replica-set seed list', () => {
        expect(() => parseRunConfig(yaml, { MONGO_URI: 'mongodb://mongo.example:27017,localhost:27017' })).toThrow('host.docker.internal');
    });
    it('requires all connections and preserves explicit external AWS endpoints', () => {
        const config = parseRunConfig(yaml.replace('http://host.docker.internal:8000', 'https://dynamodb.eu-west-1.amazonaws.com'), env);
        expect(config.workers).toMatchObject({ count: 3 });
        expect(JSON.stringify(config.runtime)).toContain('https://dynamodb.eu-west-1.amazonaws.com');
        expect(JSON.stringify(config.runtime)).not.toContain('${');
    });
    it.each(['mongodb', 'dynamodb', 'sqs'])('rejects missing %s connection', key => {
        const value = yaml.replace(new RegExp(`  ${key}:\\n(?:    [^\\n]*\\n)+`), '');
        expect(() => parseRunConfig(value, env)).toThrow(`connections.${key}`);
    });
    it('resolves secrets after parsing YAML without injecting YAML fields', () => {
        const config = parseRunConfig(yaml, { MONGO_URI: 'mongodb://user:p%23ass@mongo.example:27017/?appName=a:b' });
        expect((config.runtime.bridge as any).mongodb.uri).toContain('p%23ass');
    });
    it('names missing environment variables without exposing values', () => {
        expect(() => parseRunConfig(yaml, {})).toThrow('Missing environment variable: MONGO_URI');
    });
    it('accepts typed operational settings supplied through environment variables', () => {
        const config = parseRunConfig(yaml + 'execution:\n  workers:\n    count: ${WORKER_COUNT}\n', { ...env, WORKER_COUNT: '4' });
        expect(config.workers).toMatchObject({ count: 4 });
    });
    it.each(['localhost', '127.0.0.1', '[::1]'])('diagnoses container loopback %s', host => {
        expect(() => parseRunConfig(yaml, { MONGO_URI: `mongodb://${host}:27017` })).toThrow('host.docker.internal');
    });
    it.each(['connections: [', 'connections: {}\nconnections: {}'])('rejects invalid/duplicate YAML', value => {
        expect(() => parseRunConfig(value)).toThrow('Invalid YAML');
    });
    it('rejects unsupported AWS execution without falling back to Docker', () => {
        expect(() => parseRunConfig(yaml + 'execution:\n  provider: aws\n', env)).toThrow('not implemented');
    });
    it.each(['managed: false', 'managed: true', 'image: custom:1'])('rejects removed worker option %s', option => {
        expect(() => parseRunConfig(yaml + 'execution:\n  workers:\n    ' + option + '\n', env)).toThrow('Unknown configuration');
    });
    it.each(['    count: 0', '    count: 1.5', '    managed: nope'])('rejects invalid worker settings %s', value => {
        expect(() => parseRunConfig(yaml + 'execution:\n  workers:\n' + value + '\n', env)).toThrow();
    });
    it('rejects misspelled and unsupported orchestrator settings', () => {
        expect(() => parseRunConfig(yaml + 'execution:\n  orchestrator:\n    managed: false\n', env)).toThrow('Unknown configuration');
    });
});

describe('AWS credentials in local containers', () => {
    it('stages an explicitly selected shared profile read-only without putting credentials in arguments', async () => {
        const home = path.join(directory, 'home');
        await mkdir(path.join(home, '.aws'), { recursive: true });
        await writeFile(path.join(home, '.aws', 'credentials'), '[demo]\naws_access_key_id=example\naws_secret_access_key=example-secret\n');
        const result = await prepareAwsCredentials(directory, { AWS_PROFILE: 'demo' }, home);
        expect(result.environment.AWS_PROFILE).toBe('demo');
        expect(result.environment.AWS_SHARED_CREDENTIALS_FILE).toBe('/run/bridge/aws-credentials');
        expect(result.mounts).toHaveLength(1);
        expect(JSON.stringify(result)).not.toContain('example-secret');
        expect(await readFile(result.mounts[0].source, 'utf8')).toContain('[demo]');
        await import('node:fs/promises').then(fs => fs.chmod(result.mounts[0].source, 0o600));
    });
    it('keeps environment credentials ahead of profiles as the SDK does', async () => {
        const result = await prepareAwsCredentials(directory, { AWS_PROFILE: 'unused', AWS_ACCESS_KEY_ID: 'example', AWS_SECRET_ACCESS_KEY: 'example-secret', AWS_SESSION_TOKEN: 'session' }, directory);
        expect(result.mounts).toHaveLength(0);
        expect(result.environment.AWS_SESSION_TOKEN).toBe('session');
    });
    it('reports an unavailable selected profile without exposing credentials', async () => {
        await expect(prepareAwsCredentials(directory, { AWS_PROFILE: 'missing' }, directory)).rejects.toThrow('AWS_PROFILE requires');
    });
});

describe('migration evidence', () => {
    it.each(['PROCESSING', 'ACKNOWLEDGED'] as const)('keeps processing when PUBLISHED arrives after %s', type => {
        const progress = new RunProgress();
        progress.accept(event(type, { workUnitId: 0 }), 'worker');
        progress.accept(event('STARTED'), 'orchestrator');
        expect(progress.state).toBe('PROCESSING');
        progress.accept(event('PUBLISHED', { publishedWorkUnits: 2 }), 'orchestrator');
        expect(progress.state).toBe('PROCESSING');
        expect(progress.expected).toBe(2);
    });
    it('keeps completion after late processing and publication but still honours failure', () => {
        const progress = new RunProgress();
        progress.accept(event('STARTED'), 'orchestrator');
        progress.accept(event('PUBLISHED', { publishedWorkUnits: 1 }), 'orchestrator');
        progress.accept(event('ACKNOWLEDGED', { workUnitId: 0 }), 'worker');
        for (const late of [event('PROCESSING', { workUnitId: 0 }), event('PUBLISHED', { publishedWorkUnits: 1 }), event('ACKNOWLEDGED', { workUnitId: 0 })]) {
            progress.accept(late, late.type === 'PUBLISHED' ? 'orchestrator' : 'worker');
            expect(progress.state).toBe('COMPLETED');
            expect(progress.completedUnits).toBe(1);
        }
        progress.accept(event('FAILED'), 'worker');
        progress.accept(event('ACKNOWLEDGED', { workUnitId: 0 }), 'worker');
        expect(progress.state).toBe('FAILED');
    });
    it('requires publication and distinct acknowledgements for the same migration', () => {
        const progress = new RunProgress();
        progress.accept(event('ACKNOWLEDGED', { workUnitId: 0 }), 'worker');
        progress.accept(event('STARTED'), 'orchestrator');
        progress.accept(event('PUBLISHED', { publishedWorkUnits: 2 }), 'orchestrator');
        progress.accept(event('ACKNOWLEDGED', { workUnitId: 0 }), 'worker');
        progress.accept(event('ACKNOWLEDGED', { workUnitId: 1, migrationId: '650e8400-e29b-41d4-a716-446655440000' }), 'worker');
        expect(progress.state).not.toBe('COMPLETED');
        progress.accept(event('ACKNOWLEDGED', { workUnitId: 1 }), 'worker');
        expect(progress.state).toBe('COMPLETED');
        expect(progress.completedUnits).toBe(2);
    });
    it('does not overwrite failure with late successes', () => {
        const progress = new RunProgress();
        progress.accept(event('STARTED'), 'orchestrator');
        progress.accept(event('FAILED'), 'worker');
        progress.accept(event('PUBLISHED', { publishedWorkUnits: 0 }), 'orchestrator');
        expect(progress.state).toBe('FAILED');
    });
    it('accepts an empty completed migration only after publication', () => {
        const progress = new RunProgress();
        progress.accept(event('STARTED'), 'orchestrator');
        expect(progress.state).toBe('STARTED');
        progress.accept(event('PUBLISHED', { publishedWorkUnits: 0 }), 'orchestrator');
        expect(progress.state).toBe('COMPLETED');
    });
    it('validates event versions and identities while ignoring ordinary logs', () => {
        expect(parseEvent('normal log')).toBeUndefined();
        expect(() => parseEvent('BRIDGE_EVENT {"eventVersion":99,"type":"READY"}')).toThrow('Unsupported');
        expect(() => parseEvent('BRIDGE_EVENT {"eventVersion":1,"type":"ACKNOWLEDGED"}')).toThrow('migrationId');
    });
});

class FakeDocker implements DockerClient {
    calls: string[][] = [];
    missingImages = new Set<string>();
    pullFailure?: Error;
    infoFailure?: Error;
    logDestinations: string[] = [];
    configurations: any[] = [];
    handlers = new Map<string, (event: RuntimeEvent) => void>();
    scenario: 'success' | 'failed' | 'missing' | 'crashed' = 'success';
    async command(args: string[]): Promise<string> {
        this.calls.push(args);
        if (args[0] === 'info' && this.infoFailure) throw this.infoFailure;
        if (args[0] === 'image' && args[1] === 'inspect' && this.missingImages.has(args[2])) throw new Error('No such image');
        if (args[0] === 'pull') {
            if (this.pullFailure) throw this.pullFailure;
            this.missingImages.delete(args[1]);
        }
        if (args[0] === 'ps') return this.calls.filter(call => call[0] === 'run').map(call => call[call.indexOf('--name') + 1]).join('\n');
        if (args[0] === 'run') {
            const mount = args.find(value => value.startsWith('type=bind,source=') && value.includes('runtime.yaml'))!;
            this.configurations.push(await readFile(mount.slice('type=bind,source='.length, mount.indexOf(',target=')), 'utf8'));
        }
        if (args[0] === 'inspect') {
            const name = args.at(-1)!;
            if (args.includes('{{json .State}}')) return JSON.stringify({ Running: !name.endsWith('orchestrator') && this.scenario !== 'crashed', ExitCode: 0, OOMKilled: false });
            return name.slice(0, 40);
        }
        return 'ok';
    }
    follow(name: string, destination: string, accept: (event: RuntimeEvent) => void) {
        this.logDestinations.push(destination);
        writeFileSync(destination, `Runtime log for ${name}\n`);
        this.handlers.set(name, accept);
        if (name.includes('-worker-')) accept({ eventVersion: 1, type: 'READY' });
        else {
            accept(event('STARTED'));
            accept(event('PUBLISHED', { publishedWorkUnits: 2 }));
            for (const [worker, callback] of this.handlers) if (worker.includes('-worker-')) {
                if (this.scenario === 'failed') callback(event('FAILED'));
                if (this.scenario === 'success') for (const workUnitId of [0, 0, 1]) callback(event('ACKNOWLEDGED', { workUnitId }));
            }
        }
        return { async close() {} };
    }
}

describe('Docker execution ownership and lifecycle', () => {
    it.each([
        { worker: injectedImages.worker, orchestrator: undefined, error: 'require both' },
        { worker: undefined, orchestrator: injectedImages.orchestrator, error: 'require both' },
        { worker: '', orchestrator: injectedImages.orchestrator, error: 'Invalid Docker image reference in BRG_TEST_WORKER_IMAGE' },
        { worker: injectedImages.worker, orchestrator: '', error: 'Invalid Docker image reference in BRG_TEST_ORCHESTRATOR_IMAGE' },
        ...[' ', 'bad image:ci', '--help', 'https://registry/worker:ci', 'worker::ci', 'worker:ci\n', 'UPPER/worker:ci']
            .map(worker => ({ worker, orchestrator: injectedImages.orchestrator, error: 'Invalid Docker image reference in BRG_TEST_WORKER_IMAGE' }))
    ])('rejects incomplete or invalid E2E image variables before preparing a run ($worker, $orchestrator)', async ({ worker, orchestrator, error }) => {
        vi.stubEnv('BRG_TEST_WORKER_IMAGE', worker);
        vi.stubEnv('BRG_TEST_ORCHESTRATOR_IMAGE', orchestrator);
        const runner = vi.spyOn(dockerModule, 'runDocker').mockResolvedValue();
        await expect(runAction('unused.brg', { config: 'unused.yaml' })).rejects.toThrow(error);
        expect(runner).not.toHaveBeenCalled();
        expect(await readdir(directory)).toEqual([]);
    });
    it.each([
        injectedImages,
        { worker: 'localhost:5000/team/worker:test', orchestrator: 'localhost:5000/team/orchestrator:test' }
    ])('injects both image variables at the action boundary ($worker)', async images => {
        vi.stubEnv('BRG_TEST_WORKER_IMAGE', images.worker);
        vi.stubEnv('BRG_TEST_ORCHESTRATOR_IMAGE', images.orchestrator);
        vi.stubEnv('MONGO_URI', env.MONGO_URI);
        const source = path.join(directory, 'migration.brg');
        const config = path.join(directory, 'bridge.yaml');
        await writeFile(source, await readFile(new URL('../../../test-inputs/minimal.brg', import.meta.url), 'utf8'));
        await writeFile(config, yaml);
        const runner = vi.spyOn(dockerModule, 'runDocker').mockResolvedValue();
        await runAction(source, { config });
        expect(runner).toHaveBeenCalledExactlyOnceWith(
            path.join(directory, 'migration.plan.json'), expect.anything(), expect.objectContaining({ images })
        );
    });
    it('uses the injected local images without reading image selection from the environment', async () => {
        vi.stubEnv('BRG_TEST_WORKER_IMAGE', 'ignored-worker:unused');
        vi.stubEnv('BRG_TEST_ORCHESTRATOR_IMAGE', 'ignored-orchestrator:unused');
        const docker = new FakeDocker();
        await runDocker('plan.json', parseRunConfig(yaml, env), { docker, runsRoot: directory, images: injectedImages, tickMs: 1 });
        expect(docker.calls.filter(call => call[0] === 'image')).toEqual([
            ['image', 'inspect', injectedImages.orchestrator], ['image', 'inspect', injectedImages.worker]
        ]);
        for (const call of docker.calls.filter(call => call[0] === 'run')) {
            expect(call).toContain(call.includes('--plan') ? injectedImages.orchestrator : injectedImages.worker);
        }
        expect(docker.calls.some(call => call[0] === 'pull')).toBe(false);
    });
    it.each([injectedImages.orchestrator, injectedImages.worker])('does not pull or start containers if an injected image is missing: %s', async image => {
        const docker = new FakeDocker();
        docker.missingImages.add(image);
        await expect(runDocker('plan.json', parseRunConfig(yaml, env), { docker, runsRoot: directory, images: injectedImages }))
            .rejects.toThrow(`Injected runtime image ${image} is missing locally.`);
        expect(docker.calls.some(call => ['pull', 'run'].includes(call[0]))).toBe(false);
    });
    it.each([
        { missing: [] },
        { missing: [runtimeImages.orchestrator] },
        { missing: [runtimeImages.worker] },
        { missing: [runtimeImages.orchestrator, runtimeImages.worker] }
    ])('downloads only missing official images before starting containers ($missing)', async ({ missing }) => {
        // runDocker is driven by options, not environment variables from its caller.
        vi.stubEnv('BRG_TEST_WORKER_IMAGE', injectedImages.worker);
        vi.stubEnv('BRG_TEST_ORCHESTRATOR_IMAGE', injectedImages.orchestrator);
        const docker = new FakeDocker();
        docker.missingImages = new Set(missing);
        await runDocker('plan.json', parseRunConfig(yaml, env), { docker, runsRoot: directory, tickMs: 1 });
        expect(docker.calls.filter(call => call[0] === 'image')).toEqual([
            ['image', 'inspect', runtimeImages.orchestrator], ['image', 'inspect', runtimeImages.worker]
        ]);
        expect(docker.calls.filter(call => call[0] === 'pull')).toEqual(missing.map(image => ['pull', image]));
        expect(docker.calls.some(call => call[0] === 'build')).toBe(false);
        const firstRun = docker.calls.findIndex(call => call[0] === 'run');
        expect(firstRun).toBeGreaterThan(0);
        for (const [index, call] of docker.calls.entries()) {
            if (call[0] === 'pull') expect(index).toBeLessThan(firstRun);
            if (call[0] === 'run') expect(call).toContain(call.includes('--plan') ? runtimeImages.orchestrator : runtimeImages.worker);
        }
    });
    it.each([runtimeImages.orchestrator, runtimeImages.worker])('reports image and cause on failed pull without starting containers: %s', async image => {
        const docker = new FakeDocker();
        docker.missingImages.add(image);
        docker.pullFailure = new Error('Registry connection unavailable');
        await expect(runDocker('plan.json', parseRunConfig(yaml, env), { docker, runsRoot: directory }))
            .rejects.toThrow(`Cannot download official runtime image ${image}. Registry connection unavailable`);
        expect(docker.calls.some(call => call[0] === 'run')).toBe(false);
        expect(docker.logDestinations).toEqual([]);
    });
    it('waits for the worker download to finish before starting any container', async () => {
        const docker = new FakeDocker();
        docker.missingImages.add(runtimeImages.worker);
        let release!: () => void;
        let notifyDownloading!: () => void;
        const waiting = new Promise<void>(resolve => { release = resolve; });
        const downloading = new Promise<void>(resolve => { notifyDownloading = resolve; });
        const command = docker.command.bind(docker);
        vi.spyOn(docker, 'command').mockImplementation(async args => {
            if (args[0] === 'pull') {
                notifyDownloading();
                await waiting;
            }
            return command(args);
        });
        const run = runDocker('plan.json', parseRunConfig(yaml, env), { docker, runsRoot: directory, tickMs: 1 });
        try {
            await downloading;
            expect(docker.calls.some(call => call[0] === 'run')).toBe(false);
        } finally { release(); }
        await run;
        expect(docker.calls.some(call => call[0] === 'run')).toBe(true);
    });
    it.each(['Docker executable not found.', 'Docker daemon is unavailable.'])('reports Docker unavailability before downloading or starting: %s', async cause => {
        const docker = new FakeDocker();
        docker.infoFailure = new Error(cause);
        await expect(runDocker('plan.json', parseRunConfig(yaml, env), { docker, runsRoot: directory }))
            .rejects.toThrow(`Docker is not available. Check that Docker is installed, running and accessible. ${cause}`);
        expect(docker.calls.some(call => ['run', 'pull', 'image'].includes(call[0]))).toBe(false);
    });
    it.each([1, 2, 4])('runs %s managed workers against the configured existing services', async count => {
        const docker = new FakeDocker();
        const config = parseRunConfig(yaml, env);
        config.workers.count = count;
        await runDocker(path.join(directory, 'model with spaces.plan.json'), config, { docker, runsRoot: directory, tickMs: 1 });
        const runs = docker.calls.filter(call => call[0] === 'run');
        expect(runs).toHaveLength(count + 1);
        expect(docker.configurations).toHaveLength(count + 1);
        for (const runtime of docker.configurations) {
            expect(runtime).toContain('host.docker.internal:27017');
            expect(runtime).toContain('queue-url: http://host.docker.internal:9324/000000000000/jobs');
        }
        expect(docker.calls.filter(call => call[0] === 'rm')).toHaveLength(count + 1);
        expect(docker.calls.some(call => /create-queue|delete-queue|purge/.test(call.join(' ')))).toBe(false);
        const [id] = await readdir(directory);
        expect(JSON.parse(await readFile(path.join(directory, id, 'run.json'), 'utf8'))).toMatchObject({ state: 'COMPLETED', acknowledged: 2, published: 2 });
        expect(docker.logDestinations).toHaveLength(count + 1);
        for (const log of docker.logDestinations) {
            expect(path.dirname(log)).toBe(path.join(directory, id));
            expect(await readFile(log, 'utf8')).toContain('Runtime log');
        }
    });
    it.each(['absolute', 'relative'] as const)('creates %s custom logs while keeping state internal and preserving logs across cleanup', async kind => {
        const runsRoot = path.join(directory, 'internal runs');
        const logsRoot = path.join(directory, 'new', 'custom logs');
        if (kind === 'relative') vi.spyOn(process, 'cwd').mockReturnValue(directory);
        const logsDir = kind === 'relative' ? './new/custom logs' : logsRoot;
        const log = vi.spyOn(console, 'log').mockImplementation(() => {});
        const docker = new FakeDocker();
        await runDocker('plan.json', parseRunConfig(yaml, env), { docker, runsRoot, logsDir, tickMs: 1 });
        const [id] = await readdir(runsRoot);
        const logsDirectory = path.join(logsRoot, id);
        expect(JSON.parse(await readFile(path.join(runsRoot, id, 'run.json'), 'utf8'))).toMatchObject({ state: 'COMPLETED', logsDirectory });
        expect(await readdir(path.join(runsRoot, id))).toEqual(['run.json']);
        expect((await readdir(logsDirectory)).sort()).toEqual(docker.logDestinations.map(file => path.basename(file)).sort());
        expect(log).toHaveBeenCalledWith(`STARTED ${id}; logs: ${logsDirectory}`);
        const before = await Promise.all(docker.logDestinations.map(file => readFile(file, 'utf8')));
        await cleanupRun(id, runsRoot, docker);
        expect(await Promise.all(docker.logDestinations.map(file => readFile(file, 'utf8')))).toEqual(before);
        expect(docker.calls.some(call => call[0] === 'rm')).toBe(true);
    });
    it('keeps custom logs and internal state after runtime failure without overwriting earlier runs', async () => {
        const runsRoot = path.join(directory, 'runs');
        const logsDir = path.join(directory, 'logs');
        const config = parseRunConfig(yaml, env);
        const first = new FakeDocker();
        await runDocker('plan.json', config, { docker: first, runsRoot, logsDir, tickMs: 1 });
        const original = await Promise.all(first.logDestinations.map(file => readFile(file, 'utf8')));
        const second = new FakeDocker(); second.scenario = 'failed';
        await expect(runDocker('plan.json', config, { docker: second, runsRoot, logsDir, tickMs: 1 })).rejects.toThrow('definitive failure');
        expect(await readdir(logsDir)).toHaveLength(2);
        expect(await Promise.all(first.logDestinations.map(file => readFile(file, 'utf8')))).toEqual(original);
        const failedId = path.basename(path.dirname(second.logDestinations[0]));
        expect(JSON.parse(await readFile(path.join(runsRoot, failedId, 'run.json'), 'utf8')).state).toBe('FAILED');
        for (const file of second.logDestinations) expect(await readFile(file, 'utf8')).toContain('Runtime log');
        await cleanupRun(failedId, runsRoot, second);
        for (const file of second.logDestinations) expect(await readFile(file, 'utf8')).toContain('Runtime log');
    });
    it('rejects an invalid logs path clearly before starting any container', async () => {
        const logsDir = path.join(directory, 'blocked');
        await writeFile(logsDir, 'keep this file');
        const docker = new FakeDocker();
        const runsRoot = path.join(directory, 'runs');
        await expect(runDocker('plan.json', parseRunConfig(yaml, env), { docker, runsRoot, logsDir }))
            .rejects.toThrow(/Cannot prepare runtime logs directory .*Check the path and write permissions/);
        expect(docker.calls.some(call => call[0] === 'run')).toBe(false);
        expect(docker.logDestinations).toEqual([]);
        expect(await readFile(logsDir, 'utf8')).toBe('keep this file');
        const [id] = await readdir(runsRoot);
        expect(JSON.parse(await readFile(path.join(runsRoot, id, 'run.json'), 'utf8')).state).toBe('FAILED');
    });
    it('refuses to remove a container whose ownership changed', async () => {
        const id = 'brg-' + migrationId;
        const name = `${id}-worker-0`;
        await import('node:fs/promises').then(fs => fs.mkdir(path.join(directory, id)));
        await writeFile(path.join(directory, id, 'run.json'), JSON.stringify({ version: 1, id, containers: [name], state: 'FAILED' }));
        const calls: string[][] = [];
        const docker: DockerClient = {
            async command(args) { calls.push(args); return args[0] === 'ps' ? name : 'someone-else'; },
            follow() { throw new Error('not used'); }
        };
        await expect(cleanupRun(id, directory, docker)).rejects.toThrow('ownership mismatch');
        expect(calls.some(call => ['stop', 'rm'].includes(call[0]))).toBe(false);
    });
    it.each(['DynamoDB', 'MongoDB'])('passes a validated model targeting %s to the runtime without filtering routes', async target => {
        const runner = vi.spyOn(dockerModule, 'runDocker').mockImplementation(async (plan, config, options) => {
            const compiled = JSON.parse(await readFile(plan, 'utf8'));
            expect(compiled.planVersion).toBe(1);
            expect(compiled.target.technology).toBe(target.toLowerCase());
            expect(config.workers.count).toBe(3);
            expect(options?.logsDir).toBe('./custom logs');
            expect(options?.images).toBeUndefined();
        });
        vi.stubEnv('MONGO_URI', env.MONGO_URI);
        const source = path.join(directory, 'migration.brg');
        let model = await readFile(new URL('../../../test-inputs/minimal.brg', import.meta.url), 'utf8');
        if (target === 'MongoDB') {
            model = model.replace('target DynamoDB', 'target MongoDB')
                .replace('table "Products"', 'database "shop"')
                .replace('partitionKey "id"', 'collection "products_copy"');
        }
        await writeFile(source, model);
        const config = path.join(directory, 'bridge.yaml'); await writeFile(config, yaml);
        await runAction(source, { config, logsDir: './custom logs' });
        expect(runner).toHaveBeenCalledOnce();
        expect(JSON.parse(await readFile(path.join(directory, 'migration.plan.json'), 'utf8')).bridgeName).toBe('ProductBridge');
    });
    it.each(['failed', 'crashed'] as const)('propagates %s runtime errors and cleans only owned containers', async scenario => {
        const docker = new FakeDocker(); docker.scenario = scenario;
        await expect(runDocker('plan.json', parseRunConfig(yaml, env), { docker, runsRoot: directory, tickMs: 1 })).rejects.toThrow();
        expect(docker.calls.some(call => call[0] === 'rm')).toBe(true);
    });
    it('times out rather than treating a published migration as completed', async () => {
        const docker = new FakeDocker(); docker.scenario = 'missing';
        const config = parseRunConfig(yaml, env); config.timeoutSeconds = 0.05;
        await expect(runDocker('plan.json', config, { docker, runsRoot: directory, tickMs: 1 })).rejects.toThrow('timed out');
    });
    it('rejects cancellation and unsafe cleanup identifiers', async () => {
        const controller = new AbortController(); controller.abort();
        await expect(runDocker('plan.json', parseRunConfig(yaml, env), { docker: new FakeDocker(), runsRoot: directory, signal: controller.signal })).rejects.toThrow('cancelled');
        await expect(cleanupRun('../other', directory, new FakeDocker())).rejects.toThrow('Invalid execution');
    });
    it('validates configuration before generating a plan or invoking Docker', async () => {
        const runner = vi.spyOn(dockerModule, 'runDocker').mockResolvedValue();
        const config = path.join(directory, 'bad.yaml'); await writeFile(config, 'connections: {}');
        await expect(runAction('missing.brg', { config })).rejects.toThrow('connections.mongodb');
        expect(runner).not.toHaveBeenCalled();
        expect(await readdir(directory)).toEqual(['bad.yaml']);
    });
});
