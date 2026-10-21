import { build } from 'esbuild';
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

const result = await build({
    entryPoints: ['src/main.ts'], outfile: 'dist/cli.js', bundle: true,
    platform: 'node', format: 'esm', target: 'node22', minify: true,
    metafile: true, legalComments: 'inline',
    banner: { js: "import { createRequire as __createRequire } from 'node:module'; const require = __createRequire(import.meta.url);" }
});

// Retain the licenses of the packages actually included in the bundle.
const roots = new Set();
for (const input of Object.keys(result.metafile.inputs)) {
    if (!input.includes('node_modules/')) continue;
    let dir = path.dirname(path.resolve(input));
    while (dir !== path.dirname(dir)) {
        try {
            const pkg = JSON.parse(await readFile(path.join(dir, 'package.json'), 'utf8'));
            if (pkg.name) { roots.add(dir); break; }
        } catch {}
        dir = path.dirname(dir);
    }
}
let notices = '';
for (const root of [...roots].sort()) {
    const pkg = JSON.parse(await readFile(path.join(root, 'package.json'), 'utf8'));
    notices += `\n=== ${pkg.name}@${pkg.version} (${pkg.license ?? 'see upstream'}) ===\n`;
    let found = false;
    for (const file of ['LICENSE', 'LICENSE.md', 'LICENSE.txt', 'License.txt', 'license']) {
        try { notices += await readFile(path.join(root, file), 'utf8'); found = true; break; } catch {}
    }
    if (!found) throw new Error(`Missing license notice for ${pkg.name}`);
}
await writeFile('THIRD_PARTY_NOTICES.txt', notices);
