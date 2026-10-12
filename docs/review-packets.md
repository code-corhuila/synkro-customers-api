# HU-CLI-03 — prepared review references (no PR created)

Approved delivery: feat/customers-lifecycle-and-persistence remains at
4bffdc95459879dbaef2b58e7b35d94702ec958e with its entire published history.
Develop inspected before preparation: 5dddfc84f885db8932e4e3a567ed83c83612cfe9.
The four candidate branches form a stack; no permanent branch was modified.

## Boundaries and controls

Coverage/architecture controls move to packet 1, and the approved PostgreSQL fixture
and meaningful direct JDBC tests move to packet 2. Neither waits for packet 4.
The original six skeleton HTTP tests remain in packets 1/2. Packet 3 replaces that
HTTP behavior and those tests with the signed lifecycle suite, retaining the direct
JDBC tests. The partial two-test persistence configuration suite is replaced by the
full three-test configuration suite, preserving its assertions. No threshold,
production-class inclusion, required fixture, or existing CI event is weakened.
Push and PR events additionally match codex/review-customers-*; every candidate has
its own CI. Read-only permissions, bounded timeouts and dependency versions remain.

Counts use git diff --numstat between each candidate and its stated predecessor.
Additions AND deletions are included. The conservative code count includes Java,
POMs, workflow/deployment/environment configuration and repository attributes.
src/test and tests (including the immutable test-only DB snapshot) are test code;
docs and README are separately reported prose. No production SQL is introduced.
Use tests/measure-review.mjs to reproduce exact counts and enforce <=400.

| Packet | Production additions | Deletions | Total | Tests | Local global coverage |
|---|---:|---:|---:|---:|---:|
| 1 | 162 | 4 | 166 | 12 | 97.53% |
| 2 | 197 | 3 | 200 | 18 | 96.71% |
| 3 | 210 | 119 | 329 | 22 | 97.01% |
| 4 | 20 | 1 | 21 | 22 plus image/Compose gates | 97.01% |

Domain/application coverage is 100% on every boundary. Infrastructure is 94.12%,
95.24%, 96.10%, 96.10% respectively. Thresholds remain 90/80/60/80.

## PR 1 — ready description

Branch: codex/review-customers-01-core
SHA: 5430e00636753855dadd710d782717dc6944c1b2
Base: develop (5dddfc84f885db8932e4e3a567ed83c83612cfe9).
Title: feat(customers): define domain ports and lifecycle use cases

Customers had no implemented domain/application lifecycle. This change introduces
customer invariants, immutable identity/date behavior, pagination, use-case and
repository ports, and authorization that distinguishes human roles from the specific
Workflow SERVICE identity. Production core remains framework/database-free.

The original HTTP skeleton behavior is preserved at this review boundary. Coverage
and architecture gates are introduced now so this PR is independently verifiable.
Validation: node tests/verify-core.mjs, complete three-module reactor, six core tests
and six existing HTTP tests; no failures/errors/skips, global coverage 97.53%.
Own CI: https://github.com/code-corhuila/synkro-customers-api/actions/runs/38318477510
(SUCCESS on the SHA above). This is domain/application delivery, not full HU closure.
Relates to synkro-docs#247; do not use an automatic closing keyword.

Attribution: git cherry-pick -x of 369197c96cfd2919cff3dc6fe53e2a05e6a7028d
plus a forward verification commit with source-delivery references; AsolanoT retained.

## PR 2 — ready description

Branch: codex/review-customers-02-persistence
SHA: 1459fa6461267a78e1d68096959ad47fd0bf9266
Initial stacked base: codex/review-customers-01-core at
5430e00636753855dadd710d782717dc6944c1b2; target develop after PR 1 is incorporated.
Title: feat(customers): add transactional persistence and bounded composition

Use cases gain a real customers_schema JDBC adapter and bounded runtime composition
using customers_app. Customer/key registration commits atomically; key concurrency,
document uniqueness and rollback use the approved DB constraints. Replacement
preserves identity/date, deactivation is soft/idempotent, and repository health reads
real storage. Secret-file precedence, pool/timeouts and schema initialization controls
are explicit. No administrator credential or migration runner enters production.

The mandatory fixture uses the real approved Customers DB runner/snapshot, with
byte-preserving checkout attributes and SHA256 validation. Direct JDBC tests cover
replay races, document races, rollback, replacement, inactive ownership, isolation and
storage failure. The six existing skeleton HTTP checks still execute with fixture
settings. Validation: node tests/verify.mjs; 18 tests, no failures/errors/skips;
global coverage 96.71%, owned fixture cleanup PASS.
Own CI: https://github.com/code-corhuila/synkro-customers-api/actions/runs/38318710085
(SUCCESS on this SHA). Public HTTP lifecycle/RS256 follows in PR 3.
Relates to synkro-docs#247; no HU closure.

Attribution: forward commit explicitly references ad37da521f2b07c2c1a59c58afaac4bc3481b168
and 80d5f620d9cc9797e6853cd67731867ef9eb4d60; original author AsolanoT retained.

## PR 3 — ready description

Branch: codex/review-customers-03-http-security
SHA: 9d52cb7e66a7c8578a9196c39da50cddfd94b7cc
Initial stacked base: codex/review-customers-02-persistence at
1459fa6461267a78e1d68096959ad47fd0bf9266; target develop after PR 2 is incorporated.
Title: feat(customers): replace HTTP placeholders with secure lifecycle

The placeholder returned 501 and admitted arbitrary bearer content. This PR exposes
the approved list/register/get/replace/deactivate contract and database-backed public
health, validating local RS256 signatures/claims before authorizing any operation or
replay. Humans ADMIN/SALESPERSON have the lifecycle; only Workflow SERVICE with
customers:read can retrieve by ID. Inactive get/update returns 404, documents remain
unique after deactivation, and errors/correlation are safe and contractual.

Signed synthetic JWT and full lifecycle tests replace the original skeleton tests.
Direct JDBC verification remains. Executable restart, actual DB disconnection/503,
safe 500 and reconnection are mandatory. Validation: node tests/verify.mjs;
22 tests, no failures/errors/skips, global coverage 97.01%, runtime/cleanup PASS.
Own CI run: https://github.com/code-corhuila/synkro-customers-api/actions/runs/38318858982
(verify its completed result for the SHA above; final preparation report records it).
Deployment/image and remaining documentary handoff follow in PR 4. No HU closure.

Attribution: forward commit references ad37da521f2b07c2c1a59c58afaac4bc3481b168,
80d5f620d9cc9797e6853cd67731867ef9eb4d60 and f6184542b82fd6de685a8cf78346bd2020889988.

## PR 4 — ready description

Branch: codex/review-customers-04-delivery
SHA: the published final preparation SHA is recorded in the delivery report;
this document belongs to that commit and does not embed its own hash.
Initial stacked base: codex/review-customers-03-http-security at
9d52cb7e66a7c8578a9196c39da50cddfd94b7cc; target develop after PR 3 is incorporated.
Title: chore(customers): complete deployment and review evidence

Complete the owning image/internal deployment and operational delivery around the
verified lifecycle. Preserve the multistage Java 21/non-root Dockerfile, remove its
ignored dependency-resolution failure, use the external platform network/internal
8080 exposure, and support protected application-password/Auth-public-key files.
The image/Compose gate is mandatory in CI alongside every inherited test/control.
No production database port, volume, migration, administrator/private-key credential,
dependency pin or CODEOWNERS change is introduced.

README, design, acceptance, sanitized evidence, approved issue correction, participant
SHAs and Workflow/HU-VEN-12/infrastructure handoffs are included. Historical real
joint evidence explicitly remains attributed to f6184542b82fd6de685a8cf78346bd2020889988;
it is not relabelled as a new joint execution on these review candidates. Actual
Customers production Java and deployment files equal the approved delivery's bytes.
Products' bearer-shape authentication and final infrastructure composition remain
owner limitations; this PR does not certify platform security or close the HU.

Validation: node tests/verify.mjs (22 tests and runtime probes),
node tests/check-deployment.mjs (owning image/non-root and both Compose configurations),
and node tests/measure-review.mjs codex/review-customers-03-http-security HEAD.
Own push CI verifies this exact candidate; its URL/result/full SHA are in the final
preparation report. None of the approved delivery's CI is borrowed for this packet.
Relates to synkro-docs#247. No PR, issue edit, pin update or merge is performed here.

Attribution: forward commit records source commits 80d5f620d9cc9797e6853cd67731867ef9eb4d60,
c842d7f920a293fb708a8fb116f1263a7fd7c12c and 4bffdc95459879dbaef2b58e7b35d94702ec958e.

## Review/merge sequence and diff checks

Review and merge 1 -> 2 -> 3 -> 4. The maintainer performs merges only after own CI
and review approval; this task neither creates nor merges PRs. Preserve merge ancestry
when the team permits it, then retarget the next stacked branch to develop. Its
predecessor must be an ancestor of develop and there must be no conflicting intervening
changes. Fetch before every comparison:

```bash
git fetch origin
git merge-base --is-ancestor PREVIOUS_SHA origin/develop
git diff --stat origin/develop...CANDIDATE_BRANCH
git diff origin/develop...CANDIDATE_BRANCH
```

For measurement the script takes TWO refs; use the computed merge-base:

```bash
git merge-base origin/develop CANDIDATE_BRANCH
node tests/measure-review.mjs MERGE_BASE_SHA CANDIDATE_BRANCH
```

The planned packet remains reproducible with git diff PREVIOUS_SHA CANDIDATE_SHA.
Compare its changed-file list/code count with the current PR diff, and rerun tests/CI
if either base or candidate changes. Unrelated develop changes can change a GitHub
comparison even when the original packet is unchanged; inspect instead of discarding.

If an earlier PR is squash-merged, equivalent files do not make its original SHA an
ancestor: do not retarget blindly or publish a cumulative oversized diff. Create a
NEW follow-on branch from current origin/develop and cherry-pick -x ONLY the original
packet's commits, listed by git rev-list --reverse PREVIOUS_SHA..CANDIDATE_SHA.
Keep the existing published references/history intact, resolve conflicts explicitly,
verify range-diff/diff and <=400 again, and run new exact-SHA CI. Never rebase or
force-push these published candidates to repair ancestry. The immutable approved
delivery branch is never used as a merge source for the oversized full feature.

## Verification attempts and preservation

First core attempt compiled/tests passed but domain coverage lacked storage catalog
coverage; a meaningful safe error/catalog test resolved it. An intermediate test
compile defect was corrected before publication. First JDBC fixture attempt failed
because Windows checkout converted the approved shell runner to CRLF; .gitattributes
and original-byte restore fixed it. These are failed preparation attempts, not TDD
red claims or successful acceptance. Every published candidate passed its own local
verification; final report confirms remote CI separately. No failed candidate was
pushed. All fixtures/images use exclusive labels and remove their own resources.
Existing containers/networks/volumes and other repositories remain untouched.
