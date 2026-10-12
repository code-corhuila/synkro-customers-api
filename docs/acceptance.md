# HU-CLI-03 acceptance

This matrix belongs to the original approved delivery. The independently prepared
review boundaries, expanded test counts and own CI are in review-packets.md.

Source issue #247 and approved documentation revision are recorded in design.md.
The user supplied and approved the complete CLI-API-16/17 wording on 2026-10-12;
the prepared issue correction is in issue-247-correction.md. The original source
truncation is historical evidence, not an outstanding acceptance blocker.
Statuses below distinguish executed local verification from remote CI and joint
platform acceptance. No HU closure or permanent-branch promotion is claimed.

| Criterion | Status | Executed evidence |
|---|---|---|
| CLI-API-01 | PASS | 201/Location, active default, nullable contacts; HTTP lifecycle |
| CLI-API-02 | PASS | Same key/different valid body replay, 200, same ID/date/resource; one key/customer |
| CLI-API-03 | PASS | Eight concurrent requests, same/different bodies, exactly one 201 and one resource |
| CLI-API-04 | PASS | Active/inactive document ownership and concurrent distinct-key uniqueness: 422/detail |
| CLI-API-05 | PASS | Active retrieval, inactive/missing 404 and envelope |
| CLI-API-06 | PASS | Exact document match; inactive list visible; both active filter values |
| CLI-API-07 | PASS | Defaults, page 2, limits, unknown/repeated/invalid filters and stable date tie ordering |
| CLI-API-08 | PASS | PUT clears omitted contacts, preserves ID/date, rejects conflicts and inactive/missing rows |
| CLI-API-09 | PASS | Repeated 200 soft deletion, same row/document remains reserved |
| CLI-API-10 | PASS | Real signed synthetic JWTs, wrong key/none/HS256/RS512, missing claims, expiry, complete human write matrix |
| CLI-API-11 | PASS | Workflow identity+permission GET-by-ID only; worker/missing permission/INVENTORY denied; auth before replay |
| CLI-API-12 | PASS | Injected key-insertion constraint failure rolls back customer and key |
| CLI-API-13 | PASS | Correlation reuse/generation, 400/401/403/404/405/422/500 safe envelopes; real storage disconnection |
| CLI-API-14 | PASS | customers_app login; denied DDL/DELETE/history/cross-schema, real approved migration fixture |
| CLI-API-15 | PASS | Public database-backed health, actual 503 disconnection, executable restart persistence and recovery |
| CLI-API-16 | PASS | Actual Auth/Customers/Workflow/Products/Sales registration, inactive/missing rejection without new reservations/sales and confirmed-failure compensation; immutable SHAs recorded separately from controlled tests |
| CLI-API-17 | PASS | CI, coverage, architecture; owning Dockerfile build/non-root image and both internal Compose configurations; customers_app only, no schema generation, unchanged CODEOWNERS and other repositories' migrations |

Local command: `node tests/verify.mjs`; 17 tests, zero failures/errors/skips,
plus executable runtime probes. Coverage: domain 100%, application 100%,
infrastructure 94.81%, global 96.02%. Architecture and immutable fixture gates pass.
The runtime persists across API restart; it does not establish a production DB
backup/restore test or infrastructure deployment acceptance.

Verified code-SHA CI and actual participant SHAs/effects are recorded in evidence.md.
The final documentation commit's exact-SHA CI is reported separately in the delivery report.
Final exact-SHA CI must include check-deployment.mjs before delivery is READY FOR
REVIEW. Review and coordinated owner sign-off remain required for HU closure.
