import { readFile } from 'node:fs/promises';
import { parseDocument } from 'yaml';

export interface RunConfig {
    provider: 'docker';
    workers: { count: number };
    network?: string;
    timeoutSeconds: number;
    runtime: Record<string, unknown>;
}

type ObjectValue = Record<string, unknown>;
function object(value: unknown, name: string): ObjectValue {
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${name} must be a mapping.`);
    return value as ObjectValue;
}
function keys(value: ObjectValue, allowed: string[], name: string): void {
    for (const key of Object.keys(value)) if (!allowed.includes(key)) throw new Error(`Unknown configuration: ${name}.${key}`);
}
function text(value: unknown, name: string): string {
    if (typeof value !== 'string' || !value.trim() || /[\r\n\0]/.test(value)) throw new Error(`${name} must be a nonblank single-line string.`);
    return value;
}
function integer(value: unknown, name: string, min: number, max: number): number {
    if (typeof value === 'string' && /^\d+$/.test(value)) value = Number(value);
    if (!Number.isSafeInteger(value) || (value as number) < min || (value as number) > max) throw new Error(`${name} must be an integer between ${min} and ${max}.`);
    return value as number;
}
function address(value: unknown, name: string, schemes: string[]): string {
    const raw = text(value, name);
    let url: URL;
    try { url = new URL(raw); } catch { throw new Error(`${name} must be an absolute URL.`); }
    if (!schemes.includes(url.protocol) || !url.hostname) throw new Error(`${name} has an unsupported URL scheme.`);
    if (['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)) {
        throw new Error(`${name}: localhost refers to the container. Use host.docker.internal for a host service, or a reachable hostname and execution.network.`);
    }
    return raw;
}

function mongoAddress(value: unknown): string {
    const name = 'connections.mongodb.uri';
    const raw = text(value, name);
    const match = /^(mongodb(?:\+srv)?):\/\/([^/?#]+)/.exec(raw);
    if (!match) throw new Error(`${name} must be a MongoDB connection URI.`);
    // MongoDB supports a comma-separated seed list, unlike the WHATWG URL parser.
    const authority = match[2].slice(match[2].lastIndexOf('@') + 1);
    const hosts = authority.split(',');
    if (match[1] === 'mongodb+srv' && hosts.length !== 1) throw new Error(`${name}: SRV requires a single hostname.`);
    for (const host of hosts) address(`http://${host}`, name, ['http:']);
    return raw;
}

export function parseRunConfig(source: string, env: NodeJS.ProcessEnv = process.env): RunConfig {
    const document = parseDocument(source, { uniqueKeys: true });
    if (document.errors.length) throw new Error('Invalid YAML configuration (check syntax and duplicate keys).');
    const resolve = (value: unknown): unknown => {
        if (typeof value === 'string') return value.replace(/\$\{([A-Z_][A-Z0-9_]*)\}/g, (_, name: string) => {
            if (!env[name]?.trim()) throw new Error(`Missing environment variable: ${name}`);
            return env[name]!;
        });
        if (Array.isArray(value)) return value.map(resolve);
        if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, resolve(v)]));
        return value;
    };
    const root = object(resolve(document.toJS({ maxAliasCount: 20 })), 'configuration');
    keys(root, ['connections', 'execution'], 'configuration');
    const execution = object(root.execution ?? {}, 'execution');
    keys(execution, ['provider', 'workers', 'network', 'timeoutSeconds', 'recordsPerPartition', 'batchSize', 'visibilityTimeoutSeconds'], 'execution');
    const provider = execution.provider ?? 'docker';
    if (provider === 'aws') throw new Error('execution.provider: aws is not implemented. No Docker fallback or cloud resources were started.');
    if (provider !== 'docker') throw new Error('execution.provider must be docker (aws is reserved).');
    const workers = object(execution.workers ?? {}, 'execution.workers');
    keys(workers, ['count'], 'execution.workers');
    const connections = object(root.connections, 'connections');
    keys(connections, ['mongodb', 'dynamodb', 'sqs'], 'connections');
    const mongo = object(connections.mongodb, 'connections.mongodb');
    const dynamo = object(connections.dynamodb, 'connections.dynamodb');
    const sqs = object(connections.sqs, 'connections.sqs');
    keys(mongo, ['uri'], 'connections.mongodb');
    keys(dynamo, ['endpoint', 'region'], 'connections.dynamodb');
    keys(sqs, ['endpoint', 'region', 'queueUrl'], 'connections.sqs');
    const mongoUri = mongoAddress(mongo.uri);
    const dynamoEndpoint = dynamo.endpoint === undefined ? undefined : address(dynamo.endpoint, 'connections.dynamodb.endpoint', ['http:', 'https:']);
    const queueUrl = address(sqs.queueUrl, 'connections.sqs.queueUrl', ['http:', 'https:']);
    if (new URL(queueUrl).pathname.endsWith('.fifo')) throw new Error('Only standard SQS queues are supported.');
    const sqsEndpoint = sqs.endpoint === undefined ? undefined : address(sqs.endpoint, 'connections.sqs.endpoint', ['http:', 'https:']);
    return {
        provider: 'docker',
        workers: { count: integer(workers.count ?? 3, 'execution.workers.count', 1, 64) },
        network: execution.network === undefined ? undefined : text(execution.network, 'execution.network'),
        timeoutSeconds: integer(execution.timeoutSeconds ?? 600, 'execution.timeoutSeconds', 1, 86400),
        runtime: {
            spring: { main: { 'web-application-type': 'none' } },
            bridge: {
                mongodb: { uri: mongoUri },
                dynamodb: { ...(dynamoEndpoint ? { endpoint: dynamoEndpoint } : {}), region: text(dynamo.region, 'connections.dynamodb.region') },
                sqs: { 'queue-url': queueUrl, region: text(sqs.region, 'connections.sqs.region'), ...(sqsEndpoint ? { endpoint: sqsEndpoint } : {}),
                    'visibility-timeout-seconds': integer(execution.visibilityTimeoutSeconds ?? 120, 'execution.visibilityTimeoutSeconds', 30, 43200) },
                orchestrator: { 'records-per-partition': integer(execution.recordsPerPartition ?? 1000, 'execution.recordsPerPartition', 1, 1000000) },
                execution: { 'batch-size': integer(execution.batchSize ?? 25, 'execution.batchSize', 1, 10000) }
            }
        }
    };
}

export async function loadRunConfig(file: string): Promise<RunConfig> {
    let source: string;
    try { source = await readFile(file, 'utf8'); } catch { throw new Error(`Cannot read configuration file: ${file}`); }
    return parseRunConfig(source);
}
