# HU-CLI-03 implementation design (before production changes)

Base: 5dddfc84f885db8932e4e3a567ed83c83612cfe9. Clean checkout; no open API PRs or existing feature branch on inspection. No applicable AGENTS.md found in the workspace or its ancestors. CODEOWNERS must remain unchanged.

Approved documentation snapshot: synkro-docs main@61056c93e6e0783a9199bf56778e9494739bc458. Sources reviewed: domain entities/map, models/dictionary, Customers OpenAPI 2.0.0/shared, authentication, HTTP guidelines, cross-cutting, ADR-005/006/009/012, Java stack, testing strategy, DoR/DoD and Git/branching conventions. Customers DB checkpoint a23ba04f94ff0f150c5bafd9801cc0c6d9e98bd7; Workflow checkpoint 45aa244251851fda46212c5b6338668daaa683be. No newer merged deliveries found for those dependencies.

## Boundaries

Core holds Customer/contact invariants, trusted Caller authorization, use cases and repository port. The repository owns atomic create-once and replacement/deactivation persistence operations. A separate generic transaction port is unnecessary: no use case composes multiple repositories. Adapters implement HTTP, local RS256 validation and parameterized JDBC. App wires the ports and bounded Hikari pool. No business-operation outbound HTTP, schema creation, migrations or administrator login in production.

## Atomicity and replay

Key scope is the existing global table. No request hash, owner or expiry is introduced. The same key returns its existing customer even when a different valid body is supplied, including after deactivation; authorization precedes replay. Acquire a transaction-scoped PostgreSQL advisory lock derived from the key before lookup/insert to serialize identical keys across processes. Hash collisions only serialize unrelated work. Customer and key insertion commit together. Unique-document races roll back before a fresh key lookup; a winning key yields replay, otherwise 422 with identityDocument details. Other storage errors become sanitized INTERNAL_ERROR. No query runs in an aborted transaction.

Listing includes inactive rows unless active is supplied, uses exact document equality and registration_date DESC, customer_id DESC. PUT updates only editable fields on active rows. DELETE only updates active=true rows and retrieves already inactive rows without another write. Health checks a real business-table query.

## Initial acceptance matrix

All criteria start BLOCKED/unverified; status requires actual execution, not implementation inspection.

| Criteria | Planned evidence |
|---|---|
| CLI-API-01/02/03 | HTTP creation/defaults/optional fields and sequential/concurrent replay with real PostgreSQL |
| CLI-API-04/05 | Active/inactive uniqueness and active-only retrieval |
| CLI-API-06/07 | Exact search, both states, pagination/tie ordering and malformed/unknown filters |
| CLI-API-08/09 | Replacement, immutable ID/date, conflict, repeated soft deletion |
| CLI-API-10/11 | Signed synthetic RS256 credentials, required claims, human/SERVICE identity and operation matrix |
| CLI-API-12 | Failure inserting key rolls back customer |
| CLI-API-13 | Correlation and sanitized validation/security/storage/MVC errors |
| CLI-API-14/15 | Restricted login, denied DDL/delete/cross-schema, restart persistence and degraded health |
| CLI-API-16 | Actual Workflow + Customers + Products + Sales registration/compensation; keep BLOCKED until real evidence |
| CLI-API-17 | Source issue body currently ends midway through criterion 16; criterion 17 unavailable. Track verification/handoff separately pending complete approved wording |

## TDD and review

First red test changes the skeleton's malformed-bearer expectation from 501 to 401 and is executed before production changes. Subsequent lifecycle/domain/persistence tests are added before their implementations. Record actual commands/results in evidence.md, never reconstruct historical red runs. Review in order: core/policy, persistence/HTTP/security, real fixture/CI/operations. No PR is created automatically. Keep published history and existing authorship intact.
