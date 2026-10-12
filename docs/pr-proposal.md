# Prepared PR series — no PR created

Actual published review candidates and complete individual PR descriptions are in
review-packets.md. The approved delivery branch remains fixed at
4bffdc95459879dbaef2b58e7b35d94702ec958e. The overview below describes the complete
feature; it must not be submitted as a single oversized PR.

Title: `feat(customers): implement secure persistent customer lifecycle (HU-CLI-03)`
Target: `develop`. Delivery branch: `feat/customers-lifecycle-and-persistence`.

## Description

The Customers skeleton returned 501 and accepted arbitrary bearer content. This
delivery implements the approved registration/search/retrieval/replacement/soft
deletion contract on customers_schema through customers_app, with local RS256
authentication and separate human/Workflow authorization. Registration stores the
customer and idempotency key atomically and serializes concurrent same-key requests.

## Changes

- Framework-free domain/application ports and policies; HTTP/security and JDBC
  adapters; bounded composition and safe correlation/error handling.
- Real PostgreSQL acceptance, signed JWT matrix, rollback/concurrency, runtime
  restart/health degradation, unchanged coverage thresholds and mandatory CI fixture.
- Immutable test-only DB runner snapshot with SHA256 checks, documented settings,
  prepared owner/documentary handoffs and separately evidenced real joint acceptance.

## Validation / limits

See evidence.md and acceptance.md for local counts, coverage, exact-SHA CI and
actual participant effects. CODEOWNERS unchanged. No production migrations,
administrator credentials, outbound Customers business calls or physical deletion.
The user supplied the complete approved CLI-API-16/17 wording; the issue correction
is prepared in issue-247-correction.md and has not been published automatically.
Review/infrastructure approval and official WKF-16 updates remain with their owners.
Relates to code-corhuila/synkro-docs#247; this PR must not automatically close it.

## Review boundaries

The full feature must not be opened as one PR exceeding the team's 400-code-line
limit. Tests and copied test fixtures are excluded from production-code counts.
Preserve the original commits; do not amend/force-push to meet the limit.

1. codex/review-customers-01-core: domain/ports/policy plus coverage and architecture
   gates needed immediately. 162 additions + 4 deletions = 166 code/config lines.
2. codex/review-customers-02-persistence: JDBC/composition, mandatory real DB fixture,
   direct persistence tests and protected configuration. 197 + 3 = 200 lines.
3. codex/review-customers-03-http-security: lifecycle/security, removal of placeholders,
   signed HTTP tests and executable runtime probes. 210 + 119 = 329 lines.
4. codex/review-customers-04-delivery: remaining image/internal Compose gate,
   deployment, documentation and separately labelled historical joint evidence.

Each candidate has its own workflow, tests and coverage gates. No final-delivery CI
is attributed to an intermediate candidate. The first two intentionally retain the
baseline HTTP skeleton until the third replaces it; they are review boundaries,
not independently complete HU deployment candidates. See review-packets.md for
merge order, actual SHAs, validation commands and handling ancestry after squash.
