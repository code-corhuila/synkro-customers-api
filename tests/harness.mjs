// Own-resource cleanup and exit-code policy of tests/verify.mjs, separated from the Docker orchestration so they can be
// tested without Docker (tests/harness.test.mjs). `docker(args, {quiet, allowFailure})` is injected.

/**
 * Removes only the resources this run created. A resource whose `synkro.execution` label is not `prefix` is refused
 * (never removed) and makes the cleanup report failure. Missing resources are skipped. Returns true when every
 * existing resource was owned and removed.
 */
export function cleanupOwned({ prefix, containers, networks, docker, report = console.error }) {
    let cleaned = true;
    for (const [name, isNetwork] of [...containers.map(n => [n, false]), ...networks.map(n => [n, true])]) {
        const inspected = docker(isNetwork ? ['network', 'inspect', name] : ['inspect', name], { quiet: true, allowFailure: true });
        if (inspected.status !== 0) continue;
        const info = JSON.parse(inspected.stdout)[0], labels = isNetwork ? info.Labels : info.Config.Labels;
        if (labels?.['synkro.execution'] !== prefix) { cleaned = false; report('Unowned cleanup refused'); continue; }
        if (docker(isNetwork ? ['network', 'rm', name] : ['rm', '-fv', name], { quiet: true, allowFailure: true }).status !== 0) cleaned = false;
    }
    return cleaned;
}

/**
 * Runs `run()` and then always `cleanup()` (success and failure). Resolves the process exit code: 0 only when the run
 * succeeded and the cleanup reported success; any failure keeps a non-zero code. Errors are reported, never swallowed.
 */
export async function runWithCleanup({ run, cleanup, report = console.error }) {
    let failed = false;
    try { await run(); } catch (error) { failed = true; report(error?.message ?? String(error)); }
    finally {
        try { if (cleanup() === false) failed = true; } catch (error) { failed = true; report(`Cleanup error: ${error?.message ?? error}`); }
    }
    return failed ? 1 : 0;
}
