// Runtime 1.0.0: digests verified against the public GHCR :1.0.0 manifests.
// Update both references together after compatibility tests, then release the CLI.
export const runtimeImages = Object.freeze({
    version: '1.0.0',
    orchestrator: 'ghcr.io/bor-12/nosql-bridge-orchestrator@sha256:894dd7e89690e7d42c1a5a021c54bf547ae6915f5df62d18da7679cfe3c2a840',
    worker: 'ghcr.io/bor-12/nosql-bridge-worker@sha256:75db8579b391dbd9537034d70a9210bdfde18c7e7a49a7f634fd394cb422bf72'
});

