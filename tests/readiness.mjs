// Bounded availability waiting for the verification harness (tests/verify.mjs, tests/runtime.mjs).
//
// Why this exists. The runtime phase used to poll `/health` with `fetch(..., {signal: AbortSignal.timeout(20000)})`.
// `AbortSignal.timeout` timers are unreferenced on purpose, so while a request was pending nothing kept the event loop
// alive on the reporting host (Node 20 and 22, also on the QA baseline). The process ended with exit code 13
// before cleanup. The underlying Node/port-proxy interaction was not isolated; this harness owns its deadlines:
//   * every wait has an explicit, finite deadline enforced by an ordinary (referenced) timer, so the process cannot exit
//     before the deadline error is raised;
//   * every attempt has its own referenced timer and its own AbortController, aborted when the attempt ends, so a
//     connection or request never outlives its attempt;
//   * only availability probes are retried; anything else (business requests) fails on its first error;
//   * when the deadline passes a ReadinessTimeoutError names the wait, the deadline, the attempts and the last reason.
// No timer here is infinite and no error is swallowed: the last probe error is reported, never turned into success.

import http from 'node:http';
import https from 'node:https';

export class ReadinessTimeoutError extends Error {
    constructor(label, deadlineMs, attempts, lastReason) {
        super(`${label} was not ready within ${deadlineMs} ms after ${attempts} attempt(s); last result: ${lastReason}`);
        this.name = 'ReadinessTimeoutError';
        this.label = label; this.deadlineMs = deadlineMs; this.attempts = attempts; this.lastReason = lastReason;
    }
}

/** A short, secret-free description of a probe failure (error name and code only; never a message that could echo data). */
export function describeFailure(error) {
    const code = error?.cause?.code ?? error?.code;
    return `${error?.name ?? 'Error'}${code ? `:${code}` : ''}`;
}

function sleep(ms, signal) {
    return new Promise(resolve => {
        if (signal.aborted) return resolve();
        const done = () => { clearTimeout(timer); signal.removeEventListener('abort', done); resolve(); };
        const timer = setTimeout(done, ms);                       // referenced: keeps the loop (and the deadline) alive
        signal.addEventListener('abort', done, { once: true });
    });
}

async function runAttempt(probe, attemptTimeoutMs, overall) {
    const controller = new AbortController();
    let timer, onOverall;
    const stop = new Promise(resolve => {
        timer = setTimeout(() => resolve({ ready: false, reason: `attempt timed out after ${attemptTimeoutMs} ms` }), attemptTimeoutMs);
        onOverall = () => resolve({ ready: false, reason: 'deadline reached during the attempt' });
        overall.addEventListener('abort', onOverall, { once: true });
    });
    const outcome = Promise.resolve().then(() => probe(controller.signal)).then(
        value => ({ ready: value === true, reason: value === true ? '' : 'probe answered not ready' }),
        error => ({ ready: false, reason: describeFailure(error) }));
    try { return await Promise.race([outcome, stop]); }
    finally {
        clearTimeout(timer); overall.removeEventListener('abort', onOverall);
        controller.abort();                                        // closes whatever the attempt left open
    }
}

/**
 * Waits until `probe(signal)` resolves to exactly `true`, retrying only that probe.
 * `probe` should honour `signal` (the attempt is also abandoned by its own timer if it does not).
 */
export async function waitUntilReady({ label, probe, deadlineMs, attemptTimeoutMs = 2000, intervalMs = 250 }) {
    for (const [name, value] of Object.entries({ deadlineMs, attemptTimeoutMs, intervalMs }))
        if (!Number.isFinite(value) || value <= 0) throw new RangeError(`${name} must be a finite positive number`);
    const started = Date.now();
    const overall = new AbortController();
    const deadline = setTimeout(() => overall.abort(), deadlineMs);   // referenced on purpose: this is the live deadline
    let attempts = 0, last = 'no attempt completed';
    try {
        while (!overall.signal.aborted) {
            attempts++;
            const remaining = Math.max(1, deadlineMs - (Date.now() - started));
            const result = await runAttempt(probe, Math.min(attemptTimeoutMs, remaining), overall.signal);
            if (result.ready) return { attempts, elapsedMs: Date.now() - started };
            last = result.reason;
            await sleep(intervalMs, overall.signal);
        }
        throw new ReadinessTimeoutError(label, deadlineMs, attempts, last);
    } finally { clearTimeout(deadline); }
}

/** Availability probe for an HTTP endpoint: ready only when the status equals `status` (default 200). */
export function httpProbe(url, { status = 200 } = {}) {
    return async signal => {
        const response = await requestOnce(typeof url === 'function' ? url() : url, { signal });
        return response.status === status;
    };
}

// A dedicated socket per call prevents a shared fetch pool from opening replacement
// connections after cancellation. The request and response are closed on every path.
function requestOnce(url, options) {
    return new Promise((resolve, reject) => {
        const target = new URL(url);
        const transport = target.protocol === 'https:' ? https : http;
        const { body, ...requestOptions } = options;
        const request = transport.request(target, { ...requestOptions, agent: false }, response => {
            const chunks = [];
            response.on('data', chunk => chunks.push(chunk));
            response.on('error', reject);
            response.on('end', () => {
                resolve({ status: response.statusCode, text: Buffer.concat(chunks).toString('utf8') });
                response.destroy();
                request.destroy();
            });
        });
        request.on('error', reject);
        request.end(body);
    });
}

/**
 * One business request: a referenced timeout, a closed connection and no retry. Resolves `{status, body}` with the parsed JSON body.
 */
export async function httpRequest(url, options = {}, { timeoutMs = 20000 } = {}) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(new Error(`request timed out after ${timeoutMs} ms`)), timeoutMs);
    try {
        const response = await requestOnce(url, { ...options, signal: controller.signal });
        return { status: response.status, body: JSON.parse(response.text) };
    } finally { clearTimeout(timer); controller.abort(); }
}
