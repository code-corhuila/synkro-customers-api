// Reproducible pin of the Customers DB test fixture (tests/fixtures/customers-db) to ONE exact revision of
// code-corhuila/synkro-customers-db. The fixture is never edited by hand: it is produced from git objects by this tool and
// verified by `--check` (git objects vs. fixture) and by tests/check-quality.mjs (fixture vs. SHA256.json, offline).
//
//   node tests/pin-customers-db.mjs <revision> [--source <checkout>] [--allow-unpublished] [--accept-changes]
//   node tests/pin-customers-db.mjs --check [--source <checkout>]
//
// <revision>           a commit SHA (or any ref that resolves to one); the full 40-hex SHA is what gets recorded.
// --source             a checkout of synkro-customers-db (default: ../synkro-customers-db). It is only read.
// --allow-unpublished  pin a commit that no remote branch contains yet (draft API pull request only; recorded as such).
// --accept-changes     allow the migration bytes to differ from the previous pin (the normal case when moving from the
//                      reviewed DB branch head to its merge commit is that they do NOT differ; a difference is reported).
// --check              recompute the fixture from the recorded revision and compare byte by byte.
//
// Exported paths: what the migration runner needs (01_ddl, 02_dml, 03_dcl, 04_tcl, flyway.toml, scripts/migrate.sh) plus scripts/pending-activation (the prepared, NOT applied contacts CHECK, used by tests/compat-matrix.mjs).
// Never network-writes, never touches the source checkout's working tree.
import {spawnSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync} from 'node:fs';
import {dirname, join, relative, resolve} from 'node:path';

const root = resolve(import.meta.dirname, '..');
const fixture = resolve(root, 'tests/fixtures/customers-db');
const REPOSITORY = 'code-corhuila/synkro-customers-db';
const EXPORTED = ['01_ddl', '02_dml', '03_dcl', '04_tcl', 'flyway.toml', 'scripts/migrate.sh', 'scripts/pending-activation'];
const args = process.argv.slice(2);
const flag = n => args.includes(n);
const option = n => args.includes(n) ? args[args.indexOf(n) + 1] : undefined;
const source = resolve(option('--source') ?? resolve(root, '../synkro-customers-db'));
const generated = ['PROVENANCE.md', 'SHA256.json', 'SOURCE.json'];

function git(...a) { const r = spawnSync('git', ['-C', source, ...a], {encoding: 'buffer', maxBuffer: 64 * 1024 * 1024}); return {ok: r.status === 0, out: r.stdout, err: r.stderr.toString()}; }
function must(...a) { const r = git(...a); if (!r.ok) throw new Error(`git ${a[0]} failed: ${r.err.split('\n')[0]}`); return r.out; }
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
function listFiles(dir) { return readdirSync(dir, {withFileTypes: true}).flatMap(e => e.isDirectory() ? listFiles(join(dir, e.name)) : [join(dir, e.name)]); }
function currentFixture() {
    if (!existsSync(fixture)) return {};
    return Object.fromEntries(listFiles(fixture).map(f => relative(fixture, f).replaceAll('\\', '/')).filter(f => !generated.includes(f)).sort()
        .map(f => [f, readFileSync(resolve(fixture, f))]));
}
function snapshotAt(revision) {
    const names = must('ls-tree', '-r', '--name-only', revision, '--', ...EXPORTED).toString('utf8').split('\n').filter(Boolean).sort();
    if (!names.some(n => n === 'flyway.toml') || !names.some(n => n === 'scripts/migrate.sh')) throw new Error('revision lacks the migration runner files');
    return Object.fromEntries(names.map(n => [n, must('show', `${revision}:${n}`)]));   // raw blob bytes, no EOL conversion
}
const same = (a, b) => JSON.stringify(Object.entries(a).map(([k, v]) => [k, sha256(v)])) === JSON.stringify(Object.entries(b).map(([k, v]) => [k, sha256(v)]));

try {
    if (!existsSync(join(source, '.git')) && !git('rev-parse', '--git-dir').ok) throw new Error(`--source is not a git checkout: ${relative(process.cwd(), source) || source}`);
    if (flag('--check')) {
        const meta = JSON.parse(readFileSync(resolve(fixture, 'SOURCE.json'), 'utf8'));
        const expected = snapshotAt(meta.revision), actual = currentFixture();
        if (!same(expected, actual)) { const diff = [...new Set([...Object.keys(expected), ...Object.keys(actual)])].filter(k => !expected[k] || !actual[k] || sha256(expected[k]) !== sha256(actual[k])); throw new Error('fixture differs from ' + meta.revision + ': ' + diff.join(', ')); }
        const manifest = JSON.parse(readFileSync(resolve(fixture, 'SHA256.json'), 'utf8'));
        if (JSON.stringify(Object.keys(manifest)) !== JSON.stringify(Object.keys(expected)) || Object.entries(manifest).some(([k, h]) => h !== sha256(expected[k]))) throw new Error('SHA256.json does not match the revision');
        console.log(`Fixture matches ${REPOSITORY}@${meta.revision} byte for byte (${Object.keys(expected).length} files)${meta.published ? '' : ' -- UNPUBLISHED revision: keep the API pull request in draft'}.`);
    } else {
        const ref = args.find((a, i) => !a.startsWith('--') && args[i - 1] !== '--source');
        if (!ref) throw new Error('usage: node tests/pin-customers-db.mjs <revision> | --check   (see the header of this file)');
        const revision = must('rev-parse', '--verify', `${ref}^{commit}`).toString('utf8').trim();
        if (!/^[0-9a-f]{40}$/.test(revision)) throw new Error('revision did not resolve to a full SHA');
        const containing = git('branch', '-r', '--contains', revision).out.toString('utf8').split('\n').map(s => s.trim()).filter(Boolean);
        const published = containing.length > 0;
        if (!published && !flag('--allow-unpublished')) throw new Error(`${revision} is not contained in any remote branch of the source checkout: push it first (or run "git fetch"), or pass --allow-unpublished for a draft pull request`);
        const next = snapshotAt(revision), previous = currentFixture();
        if (Object.keys(previous).length && !same(next, previous)) {
            const changed = [...new Set([...Object.keys(next), ...Object.keys(previous)])].filter(k => !next[k] || !previous[k] || sha256(next[k]) !== sha256(previous[k]));
            console.log('The migration bytes differ from the previous pin: ' + changed.join(', '));
            if (!flag('--accept-changes')) throw new Error('refusing to change the fixture silently; review the differences and pass --accept-changes');
        } else if (Object.keys(previous).length) console.log('Migration bytes are identical to the previous pin: only the recorded revision changes.');
        rmSync(fixture, {recursive: true, force: true});
        for (const [name, bytes] of Object.entries(next)) { const target = resolve(fixture, name); mkdirSync(dirname(target), {recursive: true}); writeFileSync(target, bytes); }
        writeFileSync(resolve(fixture, 'SHA256.json'), JSON.stringify(Object.fromEntries(Object.entries(next).map(([k, v]) => [k, sha256(v)])), null, 2) + '\n');
        writeFileSync(resolve(fixture, 'SOURCE.json'), JSON.stringify({repository: REPOSITORY, revision, published, remoteBranches: published ? containing : [], paths: EXPORTED}, null, 2) + '\n');
        writeFileSync(resolve(fixture, 'PROVENANCE.md'), `# Immutable test snapshot

Source: \`${REPOSITORY}\`, revision \`${revision}\` (${published ? 'published: contained in ' + containing.slice(0, 3).join(', ') : '**UNPUBLISHED local commit — the Customers DB pull request is not on GitHub yet; keep the API pull request in draft**'}).

Produced by \`node tests/pin-customers-db.mjs <revision>\` from the git objects of that revision (raw blobs, no line-ending
conversion); \`node tests/pin-customers-db.mjs --check\` recomputes it, and \`tests/check-quality.mjs\` verifies \`SHA256.json\`
offline on every build. Exported paths: ${EXPORTED.map(p => '`' + p + '`').join(', ')}.

Never copy files by hand and never invent a future SHA: when the Customers DB pull request is merged, run the tool with the
**merge commit** on \`develop\`; the migration bytes must be identical (the tool says so, and refuses silent changes).

This fixture is test-only, outside the API artifacts and runtime image; Customers DB remains the migration owner.
`);
        console.log(`Pinned ${Object.keys(next).length} files to ${REPOSITORY}@${revision} (${published ? 'published' : 'UNPUBLISHED'}).`);
    }
} catch (e) { console.error('pin-customers-db: ' + e.message); process.exitCode = 1; }
