import { createWriteStream } from 'node:fs';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';

export type Download = (url: string, destination: string) => Promise<void>;

export const download: Download = async (url, destination) => {
    for (let redirects = 0; redirects <= 5; redirects++) {
        if (new URL(url).protocol !== 'https:') throw new Error('Runtime downloads require HTTPS.');
        const response = await fetch(url, { redirect: 'manual', signal: AbortSignal.timeout(120_000) });
        if ([301, 302, 303, 307, 308].includes(response.status)) {
            const location = response.headers.get('location');
            await response.body?.cancel();
            if (!location) throw new Error('Runtime redirect has no location.');
            url = new URL(location, url).href;
            continue;
        }
        if (!response.ok || !response.body) {
            await response.body?.cancel();
            throw new Error(`Runtime download failed: HTTP ${response.status}. Check that the expected release assets have been published.`);
        }
        await pipeline(Readable.fromWeb(response.body as never), createWriteStream(destination, { flags: 'wx' }));
        return;
    }
    throw new Error('Too many runtime download redirects.');
};
