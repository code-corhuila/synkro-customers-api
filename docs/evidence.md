# Execution evidence

This file records the original approved delivery's executions at their explicit
SHAs. Independent review-candidate verification is in review-packets.md; do not
attribute the historical final-delivery or joint run to another candidate SHA.

2026-10-12, before production changes: isolated Maven/Java 21 Docker run of CustomersHttpTest#protectedRoute_withPresentButMalformedToken_reachesTheController failed: expected 401, actual 501 (1 test, 1 failure, 0 skipped). Domain CustomerTest was also executed before its classes existed: compilation/unresolved-type failures, no green result claimed. An initial PowerShell argument parsing failure was corrected; it is not counted as behavioral red evidence. Concurrent Maven runs were stopped/finished before implementation; subsequent verification is sequential and clean.

The source issue #247 body retrieved through both GitHub connector and CLI ends at “The real Customers service replaces” under CLI-API-16. No CLI-API-17 wording exists in that retrieved revision. User-requested real participant scenarios remain required; no invented issue wording.

Lifecycle red on approved PostgreSQL fixture: creation expected 201, actual 404; 1 failure, 0 skipped. First implementation run: 7 HTTP scenarios, 1 failure (typed JSON number/boolean accepted as string); corrected Jackson textual coercion explicitly. Domain tests passed in that run. The complete subsequent green run is recorded separately below.

## Local green verification

`node tests/verify.mjs`, 2026-10-12: Maven clean verify on Java 21, real PostgreSQL
16.6-alpine and the approved Customers DB runner. Core 5/5; application/HTTP 12/12
(9 HTTP scenarios and 3 configuration tests); zero failures/errors/skips. Actual
runtime restart, lost database connectivity/503, safe 500 and reconnection recovery
all PASS. Architecture and immutable fixture checks PASS. JaCoCo lines: domain
23/23 (100%), application 24/24 (100%), infrastructure 146/154 (94.81%), global
193/201 (96.02%). No threshold was reduced and no production class was excluded.

The external-writer race test reaches a test-only key-insertion barrier, commits
the competing key from another connection, triggers a real PostgreSQL uniqueness
abort, and proves a 200 winner lookup after rollback with no orphan customer.
The disposable fixture's injected CHECK/trigger/function never enters production.

The runtime harness first failed to recover after restart because Docker changed
its ephemeral host port; it was corrected to inspect the binding again. A subsequent
constant-variable defect in that harness was corrected. Both are recorded as failed
verification attempts, not green acceptance. The final full run passed and removed
all of its labelled containers/network and temporary secrets.

## Immutable joint participant checkpoints

| Participant | Approved SHA |
|---|---|
| Customers API (successful snapshot) | f6184542b82fd6de685a8cf78346bd2020889988 |
| Auth API | 04ae6ab92daf474fb5c38ae37842d1690e7584b0 |
| Workflow | 45aa244251851fda46212c5b6338668daaa683be |
| Products API | b6b379b314716e201833be0d854953a69452559f |
| Sales API | 70bdbaba3783bfb974c338b8a460e6e9b7e5c8dd |
| Customers DB | a23ba04f94ff0f150c5bafd9801cc0c6d9e98bd7 |
| Auth DB | 5e481dba156db6f2febb00427efba637002d9a0c |
| Products DB | 0beae79920d9af83865bdebaf6b316a2b2a57592 |
| Sales DB | 9cfbde7db4bea7bacad70370c60f91f8eb917c04 |

Snapshots are GitHub archives of those exact approved develop SHAs, stored only in
ignored `.local-reference`; Customers is archived from the local delivery commit.
No other checkout is edited. `tests/joint-acceptance.mjs` builds the owning Dockerfiles,
runs real Flyway/Liquibase prerequisites, initializes a disposable ADMIN through
Auth's supported CLI and obtains actual human/Workflow JWTs through Auth HTTP.
Customers receives only the generated public key. No mock participant is used.

The first joint attempt reached a real completed saga but failed its Mongo count
assertion: interactive mongosh appended a prompt, parsed as NaN. This was a test-harness
failure, not accepted integration evidence. The helper now reads stdin in batch eval
mode without exposing credentials. A second attempt failed readiness because mongosh
returns asynchronous results; the batch helper now explicitly awaits those results.
Both failed attempts cleaned their owned resources and are not counted as acceptance.

## Real joint green verification

`node tests/joint-acceptance.mjs`, 2026-10-12, exact revisions above: PASS. Actual
Auth-issued human and Workflow SERVICE credentials, actual Customers registration,
Workflow saga, Products reservation and Sales MongoDB persistence were executed.
Completed sale: stock 20 -> 18, one sale, one RESERVED reservation. Inactive and
missing customers: validate-customer rejection, stock remains 18, one sale and one
reservation. Confirmed Sales amount-overflow rejection: register-sale compensation,
Products release, stock remains 18, one sale, one RELEASED reservation. No mocks.

Cleanup PASS for `customers-joint-62949da33fcb43cb96510b025ca14bbd`: owned
containers/network/images and temporary credential files removed. Existing Docker
resources were preserved; shared base images/build caches are intentionally retained.
The approved Products implementation only checks bearer shape, so this proves
functional interoperability, not completion of platform RS256 security. The isolated
setup is not acceptance of infrastructure's final root composition or backup policy.

## Remote code-SHA verification

GitHub Actions run [38312577569](https://github.com/code-corhuila/synkro-customers-api/actions/runs/38312577569)
completed SUCCESS for `f6184542b82fd6de685a8cf78346bd2020889988`.
Mandatory real PostgreSQL tests, coverage, fixture/architecture gates, runtime probes
and common-file checks passed. Private joint snapshots are separately evidenced
locally; ordinary CI does not claim to execute the cross-repository scenario.
The final documentation/helper commit will receive its own CI run, reported in the
delivery report without creating a self-referential commit chain.

## Approved criterion completion and delivery gate

On 2026-10-12 the user explicitly supplied the complete approved CLI-API-16/17
wording. It is preserved in issue-247-correction.md for owner publication, without
automatically editing #247. The earlier missing-wording blocker is resolved.
CLI-API-16 maps to the real joint run above; it is separate from controlled tests.

CI run [38313150337](https://github.com/code-corhuila/synkro-customers-api/actions/runs/38313150337)
completed SUCCESS for `c842d7f920a293fb708a8fb116f1263a7fd7c12c`, with 17 tests,
the same coverage, architecture/runtime checks and successful fixture cleanup.

`node tests/check-deployment.mjs` passed locally: owning Dockerfile build, non-root
synkro image/entrypoint, base and secret-file Compose configurations, internal-only
8080 exposure, external platform network, customers_app and no administrator/private
key/migration setting. Configuration validation uses synthetic values only; it does
not start the production platform or mount actual credentials. Owned image cleanup
PASS (`customers-image-test-172c5aba0b904ce69248917863fac5f2`).
This gate is now mandatory in CI for the final reviewed SHA. Production Java,
Dockerfile, Compose files and schema fixture are unchanged from the successful joint
snapshot; only documentary/test/CI additions follow it. The final exact-SHA run is
reported after publication, without another evidence-only commit loop.
