// Deterministic tests of the verification harness (no Docker, no Maven): `node --test tests/harness.test.mjs`.
// Every network scenario uses a local server on an ephemeral 127.0.0.1 port that the test itself controls.
import test from 'node:test';
import assert from 'node:assert/strict';
import net from 'node:net';
import http from 'node:http';
import { spawnSync } from 'node:child_process';
import { ReadinessTimeoutError, waitUntilReady, httpProbe, httpRequest } from './readiness.mjs';
import { cleanupOwned, runWithCleanup } from './harness.mjs';

const listen = (server, port = 0) => new Promise((ok, fail) => { server.once('error', fail); server.listen(port, '127.0.0.1', () => ok(server.address().port)); });
// net servers track their sockets so the test can always shut down (server.close() waits for open connections).
const tcpServer = handler => { const server = net.createServer(socket => { server.open.add(socket); socket.on('close', () => server.open.delete(socket)); handler(socket); }); server.open = new Set(); return server; };
const close = server => new Promise(ok => { server.closeAllConnections?.(); server.open?.forEach(socket => socket.destroy()); server.close(() => ok()); });
async function freePort() { const s = tcpServer(() => {}); const port = await listen(s); await close(s); return port; }
const until = async (condition, ms = 3000) => { const end = Date.now() + ms; while (!condition() && Date.now() < end) await new Promise(r => setTimeout(r, 10)); return condition(); };
const healthy = () => http.createServer((req, res) => { res.setHeader('content-type', 'application/json'); res.end(JSON.stringify({ status: 'UP' })); });
const fast = { attemptTimeoutMs: 100, intervalMs: 20 };

test('a service that takes time to listen is waited for: refused first, ready on a later attempt', async () => {
    const port = await freePort();
    const server = healthy();
    let attempts = 0;
    const inner = httpProbe(`http://127.0.0.1:${port}/health`);
    try {
        const probe = async signal => { try { return await inner(signal); } finally { if (++attempts === 1) await listen(server, port); } };
        const result = await waitUntilReady({ label: 'slow service', probe, deadlineMs: 5000, ...fast });
        assert.equal(attempts, 2);                                   // attempt 1: connection refused; attempt 2: ready
        assert.equal(result.attempts, 2);
    } finally { await close(server); }
});

test('a connection that is accepted and then closed is retried, and recovers when the service answers', async () => {
    let connections = 0;
    const server = tcpServer(socket => { connections++; if (connections <= 2) socket.destroy(); else socket.end('HTTP/1.1 200 OK\r\ncontent-length: 2\r\nconnection: close\r\n\r\n{}'); });
    const port = await listen(server);
    try {
        const result = await waitUntilReady({ label: 'flapping service', probe: httpProbe(`http://127.0.0.1:${port}/health`), deadlineMs: 5000, ...fast });
        assert.ok(result.attempts >= 2 && connections >= 3, `attempts=${result.attempts} connections=${connections}`);
    } finally { await close(server); }
});

test('a refused connection until the deadline fails with a clear, bounded error', async () => {
    const port = await freePort();
    const started = Date.now();
    await assert.rejects(
        waitUntilReady({ label: 'API initial startup', probe: httpProbe(`http://127.0.0.1:${port}/health`), deadlineMs: 400, ...fast }),
        error => {
            assert.ok(error instanceof ReadinessTimeoutError);
            assert.equal(error.label, 'API initial startup');
            assert.ok(error.attempts >= 2, `retried: ${error.attempts}`);
            assert.match(error.message, /API initial startup was not ready within 400 ms after \d+ attempt\(s\); last result: \S+/);
            return true;
        });
    assert.ok(Date.now() - started < 400 + 1500, 'the deadline is a real bound');
});

test('a hanging service: each attempt times out, is aborted and its connection is closed', async () => {
    const sockets = [];
    const server = tcpServer(socket => { sockets.push(socket); socket.on('error', () => {}); socket.resume(); });   // accepts and reads, never answers (a paused socket would never see the client's close)
    const port = await listen(server);
    try {
        await assert.rejects(
            waitUntilReady({ label: 'hanging service', probe: httpProbe(`http://127.0.0.1:${port}/health`), deadlineMs: 600, attemptTimeoutMs: 100, intervalMs: 20 }),
            error => error instanceof ReadinessTimeoutError && /attempt timed out after \d+ ms|deadline reached/.test(error.lastReason));
        assert.ok(sockets.length >= 2, `one connection per attempt: ${sockets.length}`);
        assert.ok(await until(() => sockets.every(s => s.destroyed)), 'every attempt closed its connection');
    } finally { await close(server); }
});

test('a probe that never settles and ignores its signal cannot make the process exit: the deadline still fires (referenced timer)', () => {
    // With an unreferenced timer this script would end with exit code 13 (unsettled top-level await) and print nothing.
    const script = `
        import { waitUntilReady } from ${JSON.stringify(new URL('./readiness.mjs', import.meta.url).href)};
        try { await waitUntilReady({ label: 'stuck', probe: () => new Promise(() => {}), deadlineMs: 300, attemptTimeoutMs: 100, intervalMs: 20 }); }
        catch (error) { console.log(error.name + '|' + error.attempts); }`;
    const run = spawnSync(process.execPath, ['--input-type=module', '-e', script], { encoding: 'utf8', timeout: 20000 });
    assert.equal(run.status, 0, run.stderr);
    assert.match(run.stdout.trim(), /^ReadinessTimeoutError\|\d+$/);
});

test('the deadline is respected even when attempts are slow', async () => {
    const started = Date.now();
    await assert.rejects(waitUntilReady({ label: 'slow probe', probe: () => new Promise(() => {}), deadlineMs: 250, attemptTimeoutMs: 10000, intervalMs: 20 }), ReadinessTimeoutError);
    assert.ok(Date.now() - started < 250 + 1500);
});

test('only availability probes are retried: a business request fails on its first error and is not repeated', async () => {
    let hits = 0;
    const server = http.createServer((req, res) => { hits++; res.statusCode = 500; res.setHeader('content-type', 'application/json'); res.end('{"error":"INTERNAL_ERROR"}'); });
    const port = await listen(server);
    try {
        assert.deepEqual(await httpRequest(`http://127.0.0.1:${port}/x`), { status: 500, body: { error: 'INTERNAL_ERROR' } });
        assert.equal(hits, 1);
    } finally { await close(server); }
    const closed = await freePort();
    await assert.rejects(httpRequest(`http://127.0.0.1:${closed}/x`, {}, { timeoutMs: 1000 }));
});

test('a business request that hangs is aborted by its own timeout and its connection is closed', async () => {
    const sockets = [];
    const server = tcpServer(socket => { sockets.push(socket); socket.on('error', () => {}); socket.resume(); });
    const port = await listen(server);
    try {
        const started = Date.now();
        await assert.rejects(httpRequest(`http://127.0.0.1:${port}/x`, {}, { timeoutMs: 150 }));
        assert.ok(Date.now() - started < 150 + 1500);
        assert.ok(sockets.length >= 1);
        assert.ok(await until(() => sockets.every(s => s.destroyed)), 'the request connection was closed');
    } finally { await close(server); }
});

test('wait parameters must be finite and positive (no infinite timers)', async () => {
    for (const bad of [{ deadlineMs: Infinity }, { deadlineMs: 0 }, { deadlineMs: NaN }, { deadlineMs: 100, attemptTimeoutMs: -1 }, { deadlineMs: 100, intervalMs: Infinity }])
        await assert.rejects(waitUntilReady({ label: 'bad', probe: async () => true, ...bad }), RangeError);
});

// ---- own-resource cleanup and exit code (fake docker: an in-memory ledger of labelled resources) ----
function fakeDocker(initial) {
    const resources = new Map(Object.entries(initial)), calls = [];
    const docker = (args, { allowFailure } = {}) => {
        calls.push(args.join(' '));
        const network = args[0] === 'network', verb = network ? args[1] : args[0], name = args.at(-1);
        if (verb === 'inspect') {
            const r = resources.get(name);
            if (!r) return { status: 1, stdout: '', stderr: 'not found' };
            return { status: 0, stdout: JSON.stringify([network ? { Labels: r.labels } : { Config: { Labels: r.labels } }]) };
        }
        if (verb === 'rm') { const ok = resources.delete(name); if (!ok && !allowFailure) throw new Error('rm failed'); return { status: ok ? 0 : 1 }; }
        throw new Error('unexpected ' + args.join(' '));
    };
    return { docker, resources, calls };
}
const mine = 'customers-api-test-aaaa', other = 'customers-api-test-bbbb';
const owned = prefix => ({ labels: { 'synkro.execution': prefix } });
const names = prefix => ({ prefix, containers: [`${prefix}-build`, `${prefix}-db`], networks: [prefix] });

test('cleanup removes the resources of this run and nothing else', () => {
    const { docker, resources } = fakeDocker({
        [`${mine}-build`]: owned(mine), [`${mine}-db`]: owned(mine), [mine]: owned(mine),
        [`${other}-build`]: owned(other), [`${other}-db`]: owned(other), [other]: owned(other), unrelated: { labels: {} } });
    assert.equal(cleanupOwned({ ...names(mine), docker }), true);
    assert.deepEqual([...resources.keys()].sort(), [`${other}-build`, `${other}-db`, other, 'unrelated'].sort());
});

test('cleanup refuses a resource with the right name but another owner label, and reports failure', () => {
    const reports = [];
    const { docker, resources } = fakeDocker({ [`${mine}-db`]: owned(other), [mine]: owned(mine) });
    assert.equal(cleanupOwned({ ...names(mine), docker, report: m => reports.push(m) }), false);
    assert.ok(resources.has(`${mine}-db`), 'the foreign-labelled resource is untouched');
    assert.ok(!resources.has(mine), 'the owned network is still removed');
    assert.deepEqual(reports, ['Unowned cleanup refused']);
});

test('cleanup reports failure when an owned resource cannot be removed, and skips missing ones', () => {
    const base = fakeDocker({ [`${mine}-db`]: owned(mine) });
    const docker = (args, options) => args[0] === 'rm' ? { status: 1 } : base.docker(args, options);
    assert.equal(cleanupOwned({ ...names(mine), docker }), false);
    assert.equal(cleanupOwned({ ...names(mine), docker: fakeDocker({}).docker }), true);   // nothing exists: nothing to do
});

test('a failing run keeps a non-zero exit, runs the cleanup and removes only its own resources', async () => {
    const { docker, resources } = fakeDocker({ [`${mine}-db`]: owned(mine), [mine]: owned(mine), [`${other}-db`]: owned(other), [other]: owned(other) });
    const reports = []; let cleanups = 0;
    const code = await runWithCleanup({
        run: async () => { throw new ReadinessTimeoutError('API initial startup', 60000, 60, 'TypeError:UND_ERR_SOCKET'); },
        cleanup: () => { cleanups++; return cleanupOwned({ ...names(mine), docker }); }, report: m => reports.push(m) });
    assert.equal(code, 1); assert.equal(cleanups, 1);
    assert.match(reports[0], /API initial startup was not ready within 60000 ms/);
    assert.deepEqual([...resources.keys()].sort(), [`${other}-db`, other].sort());
});

test('a successful run exits 0 only when the cleanup also succeeds; the cleanup runs in both cases', async () => {
    let cleanups = 0;
    assert.equal(await runWithCleanup({ run: async () => {}, cleanup: () => { cleanups++; return true; } }), 0);
    assert.equal(await runWithCleanup({ run: async () => {}, cleanup: () => { cleanups++; return false; }, report: () => {} }), 1);
    assert.equal(await runWithCleanup({ run: async () => {}, cleanup: () => { cleanups++; throw new Error('boom'); }, report: () => {} }), 1);
    assert.equal(cleanups, 3);
});

test('a failure inside the run and a failing cleanup are both reported, never swallowed', async () => {
    const reports = [];
    const code = await runWithCleanup({ run: async () => { throw new Error('phase failed'); }, cleanup: () => { throw new Error('cleanup failed'); }, report: m => reports.push(m) });
    assert.equal(code, 1);
    assert.deepEqual(reports, ['phase failed', 'Cleanup error: cleanup failed']);
});
