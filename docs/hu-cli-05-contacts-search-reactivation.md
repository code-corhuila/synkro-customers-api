# HU-CLI-05 — Customer contacts, bounded search and ADMIN reactivation (Customers API)

Parent story: [synkro-docs#304](https://github.com/code-corhuila/synkro-docs/issues/304).
Consumer of the search contract: [synkro-docs#305](https://github.com/code-corhuila/synkro-docs/issues/305) (Sales autocomplete).
Base: `synkro-customers-api@58a0ef9` (develop). Written **before** production code; the failing tests
for each rule below are written first (see "Test plan").

Status of this document: requirements and design. The contract in section 3 is a **proposal until
the Sales owner agrees it**; the agreement is recorded in the HU, never assumed here.

## 1. Domain rules (Customer aggregate)

| Rule | Decision |
|---|---|
| Required on create and replacement `PUT` | `email`, `phone`, `address` (plus the existing `name`, `identityDocument`). Missing key, JSON `null`, empty and whitespace-only values are rejected. |
| Email | ≤ 255 code points (column limit). No whitespace or control characters. Local part ≤ 64 code points, RFC 5322 *atext* characters only, dot-separated, no leading/trailing/consecutive dot. Domain: ≥ 2 ASCII labels (`[A-Za-z0-9-]`, 1–63 characters, no leading/trailing hyphen), last label ≥ 2 characters. Internationalised domains must be sent in punycode. The value is stored **exactly as sent**: no trimming, lowercasing or Unicode normalisation. |
| Phone | Stored as `text`. Exactly ten ASCII digits `[0-9]{10}` (Colombian national format, e.g. `3001234567`). Rejects letters, signs, spaces, decimals (`300123456.0`), `+57…` prefixes, non-ASCII digits (e.g. Arabic-Indic) and any other length. Leading zeros are preserved. Never trimmed, padded or truncated. |
| Address | Non-blank text, ≤ 255 code points. Blank means every code point is whitespace, a Unicode space separator or a control/format character (so NBSP-only and zero-width-only values are rejected). Stored as sent. |
| Name / document | Unchanged: required, ≤ 150 / ≤ 30 code points. Document remains unique across active **and** inactive customers. |
| Where enforced | Domain (`CustomerFields.requireComplete…`) before any persistence call, so a rejection never produces a partial write or an idempotency-key row. **The database does not enforce contacts yet**: the defence-in-depth `CHECK` is prepared and tested in the Customers DB repository (`scripts/pending-activation/`) but is *not applied* (decision "expand now, CHECK later", section 5.4). Until it is activated, this API is the only enforcement point and the only application writer of `customers_schema`. |
| Legacy rows | Reading, listing, `GET /{id}` (used by Workflow), deactivation and **reactivation never validate contacts**: a historical customer with missing contacts stays readable and keeps its UUID. It is only forced to become complete when someone replaces it with `PUT`. |

Multiple invalid fields are reported together: `400 VALIDATION_ERROR` with one `details[]` entry per field
(`required`, `invalid_format` or `too_long`) so the portal can show every error next to its field.

Error envelope compatibility: the envelope `message` is `"Invalid request fields"` **only** for these new multi-field
validations (contacts, search). Every pre-existing single-field failure (for example a missing `name`) keeps the `message` and
`details[]` it had before this story, so earlier contracts, consumers and portal messages are unaffected. (A first draft changed
the message for every validation error; that was reverted because nothing required it.)

## 2. State machine

`active ⇄ inactive`. `DELETE /customers/{id}` (existing) deactivates; the **new** `POST /customers/{id}/activate`
reactivates. Ordinary `PUT` keeps answering `404` for an inactive customer: it is not a back door to reactivate
or to edit an inactive record. UUID, `identityDocument` and `registrationDate` never change on either transition.

## 3. Contract (PROPOSAL until agreed with the Sales owner)

Both operations are served under `/api/v1/customers` and reach the browser through the gateway like every other
Customers operation.

### 3.1 `POST /api/v1/customers/search`

| Aspect | Decision |
|---|---|
| Why POST | The query contains names/documents (PII). It travels in the body so it never appears in URLs, gateway access logs, browser history or referrers. This API does not log request bodies; a test captures the log output and proves the query and the returned names are absent. |
| Auth | Human `ADMIN` or `SALESPERSON` (validated locally like every route). `INVENTORY` → 403. SERVICE tokens (Workflow) → 403. No/invalid token → 401. |
| Request | `{"query": "<text>", "status": "ACTIVE" \| "INACTIVE"}` — `status` optional, default `ACTIVE`. Unknown properties → 400. |
| `query` | Required string. Leading/trailing whitespace is trimmed; after trimming **2–100 code points**. Empty, whitespace-only, shorter, longer, non-string → `400 VALIDATION_ERROR` (`details.field = query`). |
| `status` | When present it must be a string (JSON `null` or any other type → 400). `ACTIVE` (default) is the only scope for the sale form: inactive customers are **never** suggested unless explicitly requested. `INACTIVE` is for ADMIN's reactivation workflow and answers `403 FORBIDDEN` to SALESPERSON. Any other value → 400. |
| Matching | Case- and accent-insensitive (fold = Unicode marks removed, `á→a`, `ñ→n`, `ü→u`, `ç→c`, then lowercase). *Document:* the folded document equals (`DOCUMENT_EXACT`) or starts with (`DOCUMENT_PREFIX`) the folded query. *Name:* every whitespace-separated token of the folded query is contained in the folded name, in any order (`NAME`). Wildcards (`%`, `_`, `\`) are literal. A customer that matches several ways is reported with the strongest `match`. |
| Performance | Backed by the stored columns/index of Customers DB V012/V013: ≤ 57 ms per query at 100 000 customers in the worst measured case (single connection, development machine, synthetic data); the first design took 1.5–2.7 s. See Customers DB `docs/hu-cli-05-search-benchmark.md` (method, environment, plans, write cost, storage, and what was *not* measured). The Portal's 200 ms debounce is a client courtesy, **not** a server response limit. |
| Result bound | At most **5** suggestions. `meta.hasMore` tells the caller that more than five matched (the caller should refine the text; there is no paging). |
| Order | Deterministic: `DOCUMENT_EXACT`, then `DOCUMENT_PREFIX`, then `NAME`; ties by folded name ascending, then `customerId` ascending. The document is unique, so there is at most one `DOCUMENT_EXACT`, always first: pressing Enter on a typed exact document can select `data[0]` when `match = DOCUMENT_EXACT`. |
| Response fields | Only what a selector needs: `customerId`, `name`, `identityDocument`, `active`, `match`. No email, phone or address. |
| Errors | 400 `VALIDATION_ERROR`, 401 `UNAUTHORIZED`, 403 `FORBIDDEN`, 500 `INTERNAL_ERROR`, all in the shared envelope with `traceId`. There is no 404/422: no match is `200` with `data: []`. |
| Idempotency / cancel | Read-only: no `Idempotency-Key`, safe to retry and safe for the client to abort. Stateless; no server-side session of the search. |

Example (synthetic data):

```http
POST /api/v1/customers/search
Authorization: Bearer <token>
Content-Type: application/json

{"query": "perez ana", "status": "ACTIVE"}
```

```json
{
  "data": [
    {
      "customerId": "6f1d9e0a-3c52-4c0e-9a57-0d6f0c9b7a11",
      "name": "Ana María Pérez",
      "identityDocument": "1000000001",
      "active": true,
      "match": "NAME"
    }
  ],
  "meta": { "limit": 5, "hasMore": false }
}
```

Exact document (`{"query":"1000000001"}`) → the same customer with `"match": "DOCUMENT_EXACT"`.
Invalid (`{"query":" a "}`):

```json
{
  "error": "VALIDATION_ERROR",
  "message": "Invalid request fields",
  "details": [{ "field": "query", "message": "invalid_length" }],
  "traceId": "4f1c2b7e-8a3d-4e21-9b6f-0c5d7a2e1f90"
}
```

### 3.2 `POST /api/v1/customers/{id}/activate`

| Aspect | Decision |
|---|---|
| Shape | State transition = `POST` to a sub-resource named after the transition (REST guidelines). No body, no `Idempotency-Key` (a naturally idempotent transition, not a creation). A body, if sent, is ignored. |
| Auth | **Human ADMIN only**, enforced in the API (route authorisation *and* use case). SALESPERSON, INVENTORY, SERVICE → `403`; no/invalid token → `401`. |
| Inactive customer | `200` with the full `CustomerResponse` (`active: true`), same `customerId`, document and `registrationDate`. |
| Already active | `200`, resource unchanged, **no** audit event (the transition was already done). |
| Unknown UUID | `404 NOT_FOUND`. Malformed/noncanonical UUID → `400 VALIDATION_ERROR`. |
| Attribution | The same transaction that flips `active` inserts an append-only `customers_schema.customer_audit` row: customer, action `REACTIVATED`, operator = JWT `sub`, `X-Correlation-Id`, timestamp. If the audit insert fails the activation rolls back (`500`). |
| Concurrency / retries | `UPDATE … WHERE active = false RETURNING` is the single atomic transition: of N concurrent calls exactly one flips the row and writes the audit event; the others re-read the already-active row and answer `200`. A retry after a lost response is therefore safe. |
| Document uniqueness | Reactivation never creates a row and never touches the document, so a second customer can never obtain the document of an inactive one (the unique constraint still covers inactive rows). |
| Legacy contacts | Reactivation does not require complete contacts (they are completed by the next `PUT`). |
| Errors | 400, 401, 403, 404, 500 (shared envelope). 422 is not used. |

### 3.3 Behaviour with incomplete historical customers (for the Sales selector)

Customers registered before contacts became mandatory may have no email, phone or address. For them:

| Operation | Behaviour |
|---|---|
| `GET /{id}` (Workflow), list | `200`; null contacts are returned as `null`; nothing is invented. |
| `POST /search` | They are found like any other customer (name/document); the response never carries contacts, so a selector is unaffected. |
| `DELETE /{id}` (deactivate), `POST /{id}/activate` | Work; contacts are not required. |
| `PUT /{id}` | `400 VALIDATION_ERROR` until the replacement carries valid email, phone and address (each field reported in `details[]`). |

### 3.4 Contract status (for the HU comment / Sergio — **PROPOSED, not agreed**)

Sections 3.1–3.3 are a **proposal**. Nobody has approved them yet; the agreement of the Sales owner (HU-VEN-20, #305) is recorded
in the HU when he gives it. Until then Sales must treat the contract as changeable; the JSON examples under
`customers-app/src/test/resources/contracts/` are replayed against the real server by `ContractExamplesTest`, so the document and
the implementation cannot drift silently. The Sales integration itself is **not tested** until both deliveries run together with
Sergio's concrete Sales revision.

## 4. Sales compatibility (no Workflow change)

* Workflow's `validate-customer` keeps reading `GET /api/v1/customers/{id}` with its service token and treats `404`
  (inactive **or** missing) as “the customer cannot sell”, ending the saga before any stock is reserved. It only
  parses `customerId`, `name`, `identityDocument`, `registrationDate` and `active`; the response shape for those
  fields is unchanged, and legacy customers with null contacts still validate.
* Customer becomes inactive after appearing in a suggestion → registration is rejected by the saga; the selector must
  treat that as “customer unavailable, search again”. Suggestions are advisory, never authority.
* Customer data changes after selection → Sales stores only the UUID (no snapshot): history shows current metadata.
* Direct sale with an inactive UUID → rejected by Workflow exactly as today.
* Metadata/search failure → the selector shows an error state and does not allow selection; Sales registration does
  not depend on the search being available.

## 5. Decisions (ADR-style, owner-level)

1. **Search endpoint `POST /customers/search`** — ADR-004 Decision 1 rejected a `/customers/search` route for the
   *exact-document* lookup (query parameter). Bounded name/partial search is a different need with PII in the query, so a
   body-carried route is introduced; the exact-document list filter is untouched.
2. **Folding in the database** (immutable SQL function owned by the DB repository, no extension): the API and any future
   consumer get the same semantics and the portal never downloads customers to filter them.
3. **Reactivation as a transition with an audit table** — Customers had no audit mechanism; an append-only table with
   `SELECT, INSERT` grants only is the smallest vehicle that attributes the operator without widening privileges.
4. **Expand now, CHECK later (decision approved for this delivery).** The first draft applied a contacts `CHECK` in
   Customers DB (old V011). Measured with `tests/compat-matrix.mjs`: the **previous API** answers `500 INTERNAL_ERROR` to any
   contactless create or non-ten-digit phone once that CHECK exists (and `201` on the expanded schema), so the migration made
   the previous API a non-rollback target and the DB merge order a hard constraint. This delivery therefore ships only additive,
   previous-API-compatible migrations (Customers DB V008–V014); the CHECK is prepared and tested in
   `scripts/pending-activation/` of Customers DB, outside Flyway, and is activated by a later pull request when: the new API runs
   on every instance, the inventory is complete with no invalid contacts, historical data is repaired with real information, and
   rollback compatibility is decided explicitly. The new API rejects invalid contacts without partial writes today. **Cost:** until
   activation the database does not enforce contacts; this API is the only enforcement point. **The DB constraint is not applied
   and no acceptance criterion that depends on it is closed.**
   Legacy rows: the prepared CHECK exempts rows registered before its application (a plain `NOT VALID CHECK` would also reject
   deactivating/reactivating a legacy row), and the API validates every replacement `PUT`. Details and criteria: Customers DB
   `docs/hu-cli-05-contacts-migration-policy.md`.
5. **No new pattern**: hexagonal ports/use cases, `CustomerFailure` envelope and JDBC repository are reused;
   `CustomerFailure` gains an optional list of field issues so several fields can be reported at once.
6. **Stored folded columns for the search** (Customers DB V012/V013) instead of folding every row per query: required by the
   proposed NFR-001 target (P95 < 300 ms) once volume reaches tens of thousands of customers; measured in Customers DB
   `docs/hu-cli-05-search-benchmark.md`.

## 6. Test plan (written first, red → green → refactor)

| Area | Tests |
|---|---|
| Domain (`CustomerFieldsTest`) | missing/null/empty/whitespace/NBSP contacts; email accept/reject table; phone 9/11 digits, letters, signs, spaces, `+57`, decimals, Arabic-Indic digits, leading zero kept; lengths in code points; all issues reported together. |
| Application (`CustomerServiceTest`) | authorisation matrix for `search` and `activate`; query trim/length/status validation without touching the repository; no repository call on invalid contacts. |
| HTTP + real PostgreSQL (`LifecycleHttpTest`, `CustomerContactsHttpTest`, `CustomerSearchHttpTest`, `CustomerReactivationHttpTest`) | valid create/replace; each invalid contact → 400 with field detail and **no customer and no idempotency row**; duplicate document active/inactive; replay precedence; search by name/document, case/accent, tokens, exact-first order, ≤ 5 + `hasMore`, active/inactive separation, literal wildcards, invalid bodies, SALESPERSON/INVENTORY/SERVICE/anonymous matrix; PII absent from logs; activation by ADMIN, repeat, unknown, bad UUID, denied roles, audit row exactly once under 8 concurrent calls, same UUID/document, `PUT` still 404 while inactive; legacy row (null contacts) readable/deactivatable/reactivatable. |
| Persistence (`JdbcPersistenceTest`) | search ordering/limit, activation atomicity, audit rollback when insert fails, least-privilege (no UPDATE/DELETE on audit). |
| Compatibility (`node tests/compat-matrix.mjs`) | previous API (built from `58a0ef9`) and current API against S0 (V001–V007), S1 (V001–V014) and S2 (S1 + the prepared CHECK); round trip current → previous → current on S1; rejected writes leave no row created or changed. Results: [`hu-cli-05-deployment-compatibility.md`](hu-cli-05-deployment-compatibility.md). |
| Fixture provenance (`tests/check-quality.mjs`, `node tests/pin-customers-db.mjs --check`) | the Customers DB fixture equals its SHA256 manifest, and `SOURCE.json` records a full 40-hex revision; `--check` recomputes it from the recorded revision byte by byte. |
| Contract examples | the JSON examples in `customers-app/src/test/resources/contracts/` (quoted above) are replayed against the real HTTP server by `ContractExamplesTest`: same shape, exact `error`/`message`/`details`. |

## 7. Pending / dependencies

* Agreement of section 3 with the Sales owner (HU-VEN-20) — recorded in the HU, not here.
* Customers DB migrations V008–V014 must be applied before this API can search or reactivate (create/edit with contacts keep
  working without them); the API test snapshot is pinned reproducibly to a DB revision with `tests/pin-customers-db.mjs`.
  Procedure, orders and recovery: [`hu-cli-05-deployment-compatibility.md`](hu-cli-05-deployment-compatibility.md).
* Activation of the DB contacts `CHECK` (later pull request, conditions in section 5.4).
* Inventory of QA/main data and the repair decision for incomplete legacy customers (owner decision) gate the definitive
  constraint, not this delivery.
* Joint verification with the real Sales portal is pending until both deliveries run together.
