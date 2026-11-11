export const EVENT_PREFIX = 'BRIDGE_EVENT ';
export interface RuntimeEvent {
    eventVersion: 1;
    type: 'STARTED' | 'PUBLISHED' | 'PROCESSING' | 'ACKNOWLEDGED' | 'FAILED' | 'READY';
    migrationId?: string;
    workUnitId?: number;
    publishedWorkUnits?: number;
}
export type RunState = 'STARTED' | 'PUBLISHED' | 'PROCESSING' | 'COMPLETED' | 'FAILED';

export function parseEvent(line: string): RuntimeEvent | undefined {
    if (!line.startsWith(EVENT_PREFIX)) return undefined;
    let event: RuntimeEvent;
    try { event = JSON.parse(line.slice(EVENT_PREFIX.length)); } catch { throw new Error('Invalid runtime event JSON.'); }
    if (event.eventVersion !== 1 || !['STARTED', 'PUBLISHED', 'PROCESSING', 'ACKNOWLEDGED', 'FAILED', 'READY'].includes(event.type)) throw new Error('Unsupported runtime event contract.');
    if (event.type !== 'READY' && (typeof event.migrationId !== 'string' || !/^[0-9a-f-]{36}$/i.test(event.migrationId))) throw new Error('Runtime event has no valid migrationId.');
    if (['ACKNOWLEDGED', 'PROCESSING'].includes(event.type) && (!Number.isSafeInteger(event.workUnitId) || event.workUnitId! < 0)) throw new Error('Runtime event has no valid workUnitId.');
    if (event.type === 'PUBLISHED' && (!Number.isSafeInteger(event.publishedWorkUnits) || event.publishedWorkUnits! < 0)) throw new Error('Runtime event has no valid publication count.');
    return event;
}

export class RunProgress {
    migrationId?: string;
    expected?: number;
    state: RunState = 'STARTED';
    // Worker events may arrive before the orchestrator's STARTED event is read.
    private readonly pending: RuntimeEvent[] = [];
    private readonly acknowledged = new Set<number>();
    get completedUnits(): number { return this.acknowledged.size; }
    accept(event: RuntimeEvent, role: 'orchestrator' | 'worker'): void {
        if (role === 'orchestrator' && event.type === 'STARTED') {
            if (this.migrationId && this.migrationId !== event.migrationId) throw new Error('Orchestrator changed migration identity.');
            this.migrationId = event.migrationId;
            for (const pending of this.pending.splice(0)) this.accept(pending, 'worker');
        }
        if (!this.migrationId) {
            if (event.type !== 'READY') {
                if (this.pending.length >= 1000000) throw new Error('Too many events before migration identity.');
                this.pending.push(event);
            }
            return;
        }
        if (event.migrationId !== this.migrationId) return;
        if (event.type === 'FAILED') this.state = 'FAILED';
        if (this.state === 'FAILED') return;
        if (role === 'orchestrator' && event.type === 'PUBLISHED') {
            this.expected = event.publishedWorkUnits;
            if (this.state === 'STARTED') this.state = 'PUBLISHED';
        }
        if (role === 'worker' && (event.type === 'PROCESSING' || event.type === 'ACKNOWLEDGED') && this.state !== 'COMPLETED') this.state = 'PROCESSING';
        if (role === 'worker' && event.type === 'ACKNOWLEDGED') this.acknowledged.add(event.workUnitId!);
        if (this.expected !== undefined) {
            for (const id of this.acknowledged) if (id >= this.expected) throw new Error('Acknowledgement outside the published WorkUnit range.');
            if (this.acknowledged.size === this.expected) this.state = 'COMPLETED';
        }
    }
}
