// Release identity is independent of npm and Maven versions. Publish these assets
// together only after validating the CLI/runtime contract. This release is pending.
export const expectedRuntime = Object.freeze({
    version: '1.0.0',
    releaseTag: 'runtime-v1.0.0',
    repository: 'Bor-12/nosql-bridge-mde',
    assetName: 'bridge-runtime.jar'
});

export function runtimeAssetUrl(): string {
    return `https://github.com/${expectedRuntime.repository}/releases/download/${expectedRuntime.releaseTag}/${expectedRuntime.assetName}`;
}
