// HU-CLI-05 compatibility matrix: {previous API, current API} x {schema variants}, plus a round trip on ONE database
// (current API -> previous API -> current API). Every resource is created with a unique label, is owned by this run and is
// removed only by exact name; cells run one after another. Data is synthetic.
//
//   node tests/compat-matrix.mjs                 # builds the previous API (git archive of PREVIOUS_API_REV) once
//   node tests/compat-matrix.mjs --json out.json # also writes the machine-readable result
//
// The current API jar is the one produced by `node tests/verify.mjs` (customers-app/target). Schemas come from the pinned
// fixture (tests/fixtures/customers-db), never from a sibling checkout, so the result is reproducible:
//   S0  V001-V007                       what is deployed today
//   S1  V001-V014                       the delivered expansion (additive, no contacts CHECK)
//   S2  S1 + scripts/pending-activation the contacts CHECK, NOT part of this delivery: shows what its activation will do
import {spawnSync} from 'node:child_process';
import {randomBytes, randomUUID, generateKeyPairSync, sign} from 'node:crypto';
import {existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync, rmSync} from 'node:fs';
import {resolve, join, basename} from 'node:path';
import assert from 'node:assert/strict';

const PREVIOUS_API_REV = process.env.PREVIOUS_API_REV ?? '58a0ef9f6fc05b788b2e65002468cd9c14783540';
const root = resolve(import.meta.dirname, '..');
const fixture = resolve(root, 'tests/fixtures/customers-db');
const prefix = `customers-compat-${randomUUID().replaceAll('-', '').slice(0, 16)}`;
const dir = resolve(root, '.test-run', prefix); mkdirSync(dir, {recursive: true});
const jsonOut = process.argv.includes('--json') ? process.argv[process.argv.indexOf('--json') + 1] : null;

function docker(args, {input, timeout = 600000, allowFailure = false} = {}) {
    const r = spawnSync('docker', args, {input, encoding: 'utf8', timeout, maxBuffer: 64 * 1024 * 1024, env: {...process.env, MSYS_NO_PATHCONV: '1'}});
    if (!allowFailure && (r.status !== 0 || r.error)) throw new Error(`docker ${args[0]} failed: ${(r.stderr || '').split('\n').slice(0, 3).join(' ')}`);
    return r;
}
const owned = new Set();
function track(kind, name) { owned.add(`${kind}:${name}`); return name; }
function release(kind, name) { docker(kind === 'network' ? ['network', 'rm', name] : ['rm', '-fv', name], {allowFailure: true}); owned.delete(`${kind}:${name}`); }
function cleanup() { for (const entry of [...owned].reverse()) { const i = entry.indexOf(':'); release(entry.slice(0, i), entry.slice(i + 1)); } rmSync(dir, {recursive: true, force: true}); }

// ---- schema variants: a plain psql replay of the very files Flyway runs, in version order ----
const migrationFiles = new Map();
(function walk(d) { for (const e of readdirSync(d, {withFileTypes: true})) { const p = join(d, e.name); if (e.isDirectory()) walk(p); else if (/^V\d+__.*\.sql$/.test(e.name)) migrationFiles.set(Number(basename(e.name).match(/^V(\d+)__/)[1]), p); } })(fixture);
const base = [1, 2, 3, 4, 5, 6, 7];
const expansion = [...migrationFiles.keys()].sort((a, b) => a - b);
const pendingCheck = readFileSync(resolve(fixture, 'scripts/pending-activation/require-contacts-check.sql'), 'utf8');
const variants = {
    'S0 base V001-V007': {versions: base},
    'S1 expansion V001-V014': {versions: expansion},
    'S2 S1 + contacts CHECK (future, not delivered)': {versions: expansion, after: pendingCheck},
};

// ---- API jars ----
function previousJar() {
    const cache = resolve(root, '.test-cache/previous-api.jar'), stamp = resolve(root, '.test-cache/previous-api.rev');
    if (existsSync(cache) && existsSync(stamp) && readFileSync(stamp, 'utf8') === PREVIOUS_API_REV) return cache;
    mkdirSync(resolve(root, '.test-cache'), {recursive: true});
    const tar = spawnSync('git', ['-C', root, 'archive', '--format=tar', PREVIOUS_API_REV, 'pom.xml', 'customers-core', 'customers-adapters', 'customers-app'], {maxBuffer: 256 * 1024 * 1024});
    if (tar.status !== 0) throw new Error('git archive of the previous API failed');
    const builder = track('container', `${prefix}-build`);
    docker(['create', '--name', builder, '--label', `synkro.execution=${prefix}`, '-w', '/src', 'maven:3.9-eclipse-temurin-21', 'mvn', '-B', '-q', '-Dmaven.repo.local=/tmp/m2', '-DskipTests', '-Djacoco.skip=true', 'package']);
    docker(['cp', resolve(root, '.test-cache/m2'), `${builder}:/tmp/m2`], {allowFailure: true});
    docker(['cp', '-', `${builder}:/src`], {input: tar.stdout});
    const r = docker(['start', '-a', builder], {allowFailure: true});
    if (r.status !== 0) throw new Error('previous API build failed: ' + (r.stdout + r.stderr).slice(-600));
    docker(['cp', `${builder}:/src/customers-app/target/customers-app-0.1.0-SNAPSHOT.jar`, cache]);
    writeFileSync(stamp, PREVIOUS_API_REV);
    release('container', builder);
    return cache;
}
const currentJar = resolve(root, 'customers-app/target/customers-app-0.1.0-SNAPSHOT.jar');
if (!existsSync(currentJar)) throw new Error('Run `node tests/verify.mjs` first: the current API jar is missing');
const apis = {'previous API': previousJar(), 'current API': currentJar};

// ---- key pair / token shared by every cell ----
const keys = generateKeyPairSync('rsa', {modulusLength: 2048});
const pem = resolve(dir, 'public.pem'); writeFileSync(pem, keys.publicKey.export({type: 'spki', format: 'pem'}));
function token(role) {
    const now = Math.floor(Date.now() / 1000), head = Buffer.from(JSON.stringify({alg: 'RS256'})).toString('base64url');
    const body = Buffer.from(JSON.stringify({sub: randomUUID(), roles: [role], permissions: [], iat: now - 1, exp: now + 3600, jti: randomUUID()})).toString('base64url');
    return `${head}.${body}.${sign('RSA-SHA256', Buffer.from(`${head}.${body}`), keys.privateKey).toString('base64url')}`;
}
const ADMIN = token('ADMIN');

// ---- building blocks ----
async function startDatabase({versions, after}) {
    const tag = `${prefix}-${randomBytes(3).toString('hex')}`;
    const network = track('network', tag), db = track('container', `${tag}-db`);
    const admin = randomBytes(18).toString('hex'), password = randomBytes(18).toString('hex');
    docker(['network', 'create', '--label', `synkro.execution=${prefix}`, network]);
    docker(['run', '-d', '--name', db, '--label', `synkro.execution=${prefix}`, '--network', network, '--network-alias', 'db', '-e', `POSTGRES_PASSWORD=${admin}`, '-e', 'POSTGRES_DB=synkro', '--tmpfs', '/var/lib/postgresql/data', 'postgres:16.6-alpine']);
    for (let i = 0; i < 60 && docker(['exec', db, 'pg_isready', '-U', 'postgres'], {allowFailure: true}).status !== 0; i++) await new Promise(r => setTimeout(r, 1000));
    const psql = sql => docker(['exec', '-i', db, 'psql', '-U', 'postgres', '-d', 'synkro', '-v', 'ON_ERROR_STOP=1', '-q', '-At'], {input: sql}).stdout.trim();
    psql(`REVOKE CREATE ON SCHEMA public FROM PUBLIC; CREATE ROLE customers_app LOGIN INHERIT PASSWORD '${password}' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS; ALTER ROLE customers_app SET search_path=customers_schema;`);
    // V007 revokes privileges on Flyway's history table, which Flyway itself creates; a bare stub stands in for it.
    for (const v of versions) psql((v === 7 ? 'CREATE TABLE IF NOT EXISTS customers_schema.flyway_schema_history(installed_rank int);\n' : '') + `SET search_path=customers_schema,public;\n${readFileSync(migrationFiles.get(v), 'utf8')}`);
    // Historical customers (registered before any cutover): one active and one inactive, with no contacts at all.
    psql(`INSERT INTO customers_schema.customer(name,identity_document,registration_date,active) VALUES ('Historical Active','H-ACTIVE','2020-01-01T00:00:00Z',true),('Historical Inactive','H-INACTIVE','2020-01-01T00:00:00Z',false)`);
    if (after) psql(after);
    const ids = Object.fromEntries(psql(`SELECT identity_document||'='||customer_id FROM customers_schema.customer`).split('\n').map(l => l.split('=')));
    return {tag, network, db, password, psql, ids};
}
function stopDatabase(d) { release('container', d.db); release('network', d.network); }

async function startApi(jar, d, label) {
    const app = track('container', `${d.tag}-${label}`);
    docker(['create', '--name', app, '--label', `synkro.execution=${prefix}`, '--network', d.network, '-e', 'SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/synkro', '-e', 'SPRING_DATASOURCE_USERNAME=customers_app', '-e', `SPRING_DATASOURCE_PASSWORD=${d.password}`, '-e', 'JWT_PUBLIC_KEY_FILE=/run/public.pem', '--publish', '127.0.0.1::8080', '--mount', `type=bind,source=${pem},target=/run/public.pem,readonly`, 'eclipse-temurin:21-jre-alpine', 'java', '-jar', '/app.jar']);
    docker(['cp', jar, `${app}:/app.jar`]); docker(['start', app]);
    const port = JSON.parse(docker(['inspect', app]).stdout)[0].NetworkSettings.Ports['8080/tcp'][0].HostPort, base = `http://127.0.0.1:${port}`;
    let up = false; for (let i = 0; i < 90 && !up; i++) { try { up = (await fetch(base + '/health', {signal: AbortSignal.timeout(2000)})).status === 200; } catch {} if (!up) await new Promise(r => setTimeout(r, 1000)); }
    const call = async (method, path, body, extra = {}) => {
        try {
            const r = await fetch(base + path, {method, signal: AbortSignal.timeout(20000), headers: {Authorization: `Bearer ${ADMIN}`, 'Content-Type': 'application/json', ...extra}, body: body === undefined ? undefined : JSON.stringify(body)});
            const text = await r.text(); let json = {}; try { json = JSON.parse(text); } catch {}
            return {status: r.status, json, text, label: `${r.status}${json.error ? ' ' + json.error : ''}`};
        } catch (e) { return {status: 0, json: {}, text: '', label: 'ERR ' + e.name}; }
    };
    return {up, call, stop: () => release('container', app)};
}
const doc = () => 'D' + randomBytes(6).toString('hex');
const full = {name: 'Synthetic Person', email: 'synthetic@example.test', phone: '3001234567', address: 'Synthetic street 1'};
const create = (api, body) => api.call('POST', '/api/v1/customers', body, {'Idempotency-Key': randomUUID()});

// ---- one matrix cell ----
async function cell(jar, variant) {
    const d = await startDatabase(variant), api = await startApi(jar, d, 'api'), probes = {};
    try {
        if (!api.up) { for (const k of ['create with contacts', 'create without contacts', 'create with phone "300-123-4567"', 'get historical customer', 'put historical customer, still without contacts', 'deactivate historical customer', 'search by name', 'reactivate historical customer']) probes[k] = 'API did not start'; return probes; }
        probes['create with contacts'] = (await create(api, {...full, identityDocument: doc()})).label;
        probes['create without contacts'] = (await create(api, {name: 'Synthetic Person', identityDocument: doc()})).label;
        probes['create with phone "300-123-4567"'] = (await create(api, {...full, phone: '300-123-4567', identityDocument: doc()})).label;
        probes['get historical customer'] = (await api.call('GET', `/api/v1/customers/${d.ids['H-ACTIVE']}`)).label;
        probes['put historical customer, still without contacts'] = (await api.call('PUT', `/api/v1/customers/${d.ids['H-ACTIVE']}`, {name: 'Historical Active', identityDocument: 'H-ACTIVE'})).label;
        probes['deactivate historical customer'] = (await api.call('DELETE', `/api/v1/customers/${d.ids['H-ACTIVE']}`)).label;
        probes['search by name'] = (await api.call('POST', '/api/v1/customers/search', {query: 'synthetic'})).label;
        probes['reactivate historical customer'] = (await api.call('POST', `/api/v1/customers/${d.ids['H-INACTIVE']}/activate`)).label;
        return probes;
    } finally { api.stop(); stopDatabase(d); }
}

// ---- round trip on one database: current API -> previous API (rollback) -> current API ----
async function roundTrip() {
    const steps = [], note = (step, outcome) => { steps.push({step, outcome}); };
    const d = await startDatabase(variants['S1 expansion V001-V014']);
    try {
        // 1. current API on the expanded schema
        let api = await startApi(currentJar, d, 'new-1'); assert.ok(api.up, 'current API did not start');
        const a = await create(api, {...full, name: 'Ana Maria Perez', identityDocument: doc()}); assert.equal(a.status, 201);
        const b = await create(api, {...full, name: 'Beatriz Gomez', identityDocument: doc()}); assert.equal(b.status, 201);
        assert.equal((await api.call('DELETE', `/api/v1/customers/${b.json.customerId}`)).status, 200);
        assert.equal((await api.call('POST', `/api/v1/customers/${b.json.customerId}/activate`)).status, 200);
        const found = await api.call('POST', '/api/v1/customers/search', {query: 'beatriz'}); assert.equal(found.json.data?.[0]?.customerId, b.json.customerId);
        note('current API: create with contacts, deactivate, reactivate (audited), search', 'ok');
        const before = d.psql(`SELECT count(*) FROM customers_schema.customer`);
        const badPhone = await create(api, {...full, phone: '300-123-4567', identityDocument: doc()}), noContacts = await create(api, {name: 'No Contacts', identityDocument: doc()});
        const badPut = await api.call('PUT', `/api/v1/customers/${a.json.customerId}`, {...full, name: 'Changed Name', email: 'not-an-email', identityDocument: a.json.identityDocument});
        assert.deepEqual([badPhone.status, noContacts.status, badPut.status], [400, 400, 400]);
        assert.equal(d.psql(`SELECT count(*) FROM customers_schema.customer`), before, 'a rejected write must not create rows');
        assert.equal(d.psql(`SELECT name FROM customers_schema.customer WHERE customer_id='${a.json.customerId}'`), 'Ana Maria Perez', 'a rejected PUT must not change the row');
        note('current API: invalid phone / no contacts / invalid email on PUT -> 400 VALIDATION_ERROR, no row created, no row changed', 'ok');
        api.stop();
        // 2. rollback: the previous API on the very same database
        api = await startApi(apis['previous API'], d, 'old'); assert.ok(api.up, 'previous API did not start on the expanded schema');
        const reads = [(await api.call('GET', `/api/v1/customers/${a.json.customerId}`)).status, (await api.call('GET', `/api/v1/customers/${b.json.customerId}`)).status];
        assert.deepEqual(reads, [200, 200]);
        assert.equal((await api.call('PUT', `/api/v1/customers/${a.json.customerId}`, {...full, name: 'Ana Maria Perez Renamed', identityDocument: a.json.identityDocument})).status, 200);
        const legacy = await create(api, {name: 'Legacy Writer', identityDocument: doc()}); assert.equal(legacy.status, 201);   // contactless: the previous API accepts it
        assert.equal((await api.call('DELETE', `/api/v1/customers/${b.json.customerId}`)).status, 200);
        assert.equal(Number(d.psql(`SELECT count(*) FROM customers_schema.customer_audit`)), 1, 'the audit written by the current API stays');
        note('previous API after rollback: reads rows created by the current API, PUT, contactless create (201), deactivate', 'ok');
        api.stop();
        // 3. roll forward again: the current API sees what the previous API wrote
        api = await startApi(currentJar, d, 'new-2'); assert.ok(api.up);
        const generated = d.psql(`SELECT name_search || '|' || document_search FROM customers_schema.customer WHERE customer_id='${legacy.json.customerId}'`);
        assert.equal(generated.split('|')[0], 'legacy writer', 'search columns are filled by the database for rows the previous API wrote');
        assert.equal((await api.call('GET', `/api/v1/customers/${legacy.json.customerId}`)).status, 200);
        assert.equal((await api.call('POST', '/api/v1/customers/search', {query: 'legacy writer'})).json.data?.[0]?.customerId, legacy.json.customerId);
        assert.equal((await api.call('PUT', `/api/v1/customers/${legacy.json.customerId}`, {name: 'Legacy Writer', identityDocument: legacy.json.identityDocument})).status, 400);
        assert.equal((await api.call('PUT', `/api/v1/customers/${legacy.json.customerId}`, {...full, name: 'Legacy Writer', identityDocument: legacy.json.identityDocument})).status, 200);
        assert.equal((await api.call('POST', `/api/v1/customers/${b.json.customerId}/activate`)).status, 200);
        assert.equal(Number(d.psql(`SELECT count(*) FROM customers_schema.customer_audit WHERE customer_id='${b.json.customerId}'`)), 2, 'a second real transition adds exactly one more audit row');
        note('current API again: finds the contactless row written by the previous API, rejects an incomplete PUT (400) and accepts a complete one, reactivates (second audit row)', 'ok');
        api.stop();
    } finally { stopDatabase(d); }
    return steps;
}

const result = {previousApiRevision: PREVIOUS_API_REV, schemas: Object.fromEntries(Object.entries(variants).map(([k, v]) => [k, v.versions.map(n => 'V' + String(n).padStart(3, '0')).join(',') + (v.after ? ' + pending-activation CHECK' : '')])), cells: {}};
try {
    for (const [variant, definition] of Object.entries(variants)) for (const [apiName, jar] of Object.entries(apis)) {
        process.stdout.write(`${apiName} x ${variant} ... `);
        result.cells[`${apiName} | ${variant}`] = await cell(jar, definition);
        console.log('done');
    }
    const probeNames = Object.keys(Object.values(result.cells)[0]);
    console.log('\nColumns: ' + Object.keys(result.cells).map((c, i) => `[${i + 1}] ${c}`).join('  '));
    for (const p of probeNames) console.log(`${p.padEnd(52)} ` + Object.values(result.cells).map(c => c[p].padEnd(24)).join(' '));
    process.stdout.write('\nround trip on S1 (current -> previous -> current) ... ');
    result.roundTrip = await roundTrip(); console.log('PASS');
    for (const s of result.roundTrip) console.log(` - ${s.step}: ${s.outcome}`);
    if (jsonOut) writeFileSync(jsonOut, JSON.stringify(result, null, 2));
} finally { cleanup(); }
