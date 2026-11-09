# HU-CLI-05 — Compatibility, deployment order and fixture procedure

Refs [code-corhuila/synkro-docs#304](https://github.com/code-corhuila/synkro-docs/issues/304). Evidence produced by
`node tests/compat-matrix.mjs` (disposable PostgreSQL 16.6 and containers, synthetic data; the previous API is built from
`58a0ef9`, the current one from this branch; schemas come from the pinned fixture).

## 1. Decision recorded: expand now, CHECK later

The first draft applied a contacts `CHECK` (old V011). It is the only constraint that breaks the previous API: with it, any
create without contacts, or with a phone that is not exactly ten digits, answers `500 INTERNAL_ERROR` on the previous API
(measured, matrix column 5). It was removed from the migration path **before** any of V008–V013 reached a shared environment
(unpublished branch; `develop`, `qa` and `main` do not contain them; the shared dev history ends at V007), so renumbering the new
ones was safe. It is kept, tested, in Customers DB `scripts/pending-activation/` and activated by a later pull request.
**The database does not enforce contacts yet; the API does.** The DB constraint is not applied and nothing depending on it is closed.

Authorised writers of `customers_schema`: `customers_app` (member of `customers_writer`, used only by this API) and the Flyway
administrative login for migrations and owner-approved repairs. No other domain has privileges there (Workflow, Auth and Products
verify the denial); Sales stores only the customer UUID.

## 2. Matrix (status codes; HTTP `error` code after the status)

Schemas: **S0** = V001–V007 (deployed today). **S1** = V001–V014 (this delivery, expansion only). **S2** = S1 + the
prepared CHECK (future activation; *not delivered*).

| Probe (ADMIN token) | prev × S0 | cur × S0 | prev × S1 | cur × S1 | prev × S2 | cur × S2 |
|---|---|---|---|---|---|---|
| create with contacts | 201 | 201 | 201 | 201 | 201 | 201 |
| create without contacts | 201 | 400 VALIDATION_ERROR | 201 | 400 VALIDATION_ERROR | **500 INTERNAL_ERROR** | 400 VALIDATION_ERROR |
| create with phone `300-123-4567` | 201 | 400 VALIDATION_ERROR | 201 | 400 VALIDATION_ERROR | **500 INTERNAL_ERROR** | 400 VALIDATION_ERROR |
| get historical customer (no contacts) | 200 | 200 | 200 | 200 | 200 | 200 |
| put historical customer, still without contacts | 200 | 400 VALIDATION_ERROR | 200 | 400 VALIDATION_ERROR | 200 | 400 VALIDATION_ERROR |
| deactivate historical customer | 200 | 200 | 200 | 200 | 200 | 200 |
| search by name | 405 | 500 INTERNAL_ERROR | 405 | 200 | 405 | 200 |
| reactivate historical inactive customer | 404 | 500 INTERNAL_ERROR | 404 | 200 | 404 | 200 |

Reading it:

- **prev × S1 is fully compatible**: the expansion can be applied while the previous API keeps serving (the generated search
  columns are ignored by it and filled by the database for whatever it writes).
- **cur × S0 (new API before the database)**: create, read, update and deactivate work (the API validates by itself); only the
  two new operations fail, with a safe `500` and no partial effects. This is why the database goes first.
- **prev × S2 is the future cost** of activating the CHECK: it stops tolerating the previous API's contactless writes. That is the
  explicit rollback-compatibility decision required before activation.
- A historical customer without contacts is readable, searchable, deactivatable and reactivatable everywhere; only a replacement
  `PUT` by the new API demands complete contacts.

### Round trip on one database (S1): current API → previous API → current API — PASS

1. Current API: create with contacts, deactivate, reactivate (audit row written), search finds it.
2. Current API rejects invalid phone / missing contacts / invalid email on `PUT` with `400 VALIDATION_ERROR`: **no row created, no
   row changed** (row count and name verified in the database).
3. **Rollback** to the previous API on the same database: reads the rows the current API wrote, `PUT` works, a contactless create
   returns `201`, deactivation works; the audit written by the current API stays.
4. Back to the current API: finds the contactless row written by the previous API (the database filled `name_search`), rejects an
   incomplete `PUT` with `400` and accepts a complete one, and a second real reactivation adds exactly one more audit row.

## 3. Partial update and rollback

- **Mixed fleet** (previous and current instances at once) on S1: both work. A contactless write that reaches a previous instance
  is stored; the next `PUT` of that customer through the current API must complete its contacts.
- **Rollback of the API** at any phase before the CHECK is activated: safe (round trip above). After activation it is not, for
  contactless writes (column 5).
- **Rollback of the database**: only on disposable instances (the `U` scripts, reverse order); in shared environments use a
  corrective migration and a verified backup. Audit rows are evidence and are not deleted.

## 4. Four different orders (do not confuse them)

| Order | Sequence |
|---|---|
| **Pull request review / merge** | 1) Customers DB PR → `develop`. 2) Customers API PR (draft until step 1 is merged **and** the fixture is re-pinned, §5) → `develop`. 3) Customers Portal PR → `develop` (independent of the others for review; it needs the API for any real use). The Sales PR (HU-VEN-20) is not mine and depends on the contract being agreed. |
| **Migration application / deployment** (per environment, after each repo is promoted by its owners) | 1) Apply V008–V014 with the approved runner (`migrate`, then `validate`), in a quiet window (V012 rewrites the table under an exclusive lock: ≈ 0.5 s per 20 000 customers measured). 2) Deploy the new API (rolling is fine). 3) Deploy the new Portal. 4) Verify. 5) *Later, separately:* activate the contacts CHECK (conditions in `scripts/pending-activation/README.md` of Customers DB). |
| **Post-deployment verification** | Health `200`; create with valid contacts `201`; invalid contacts `400` with `details[]` and no new row; `POST /customers/search` `200` for ADMIN and SALESPERSON, `403` for INVENTORY; reactivation `200` for ADMIN and `403` for SALESPERSON; Workflow `GET /{id}` unchanged. Inventory script run and recorded. |
| **Failure recovery** | New API misbehaves → redeploy the previous API (compatible with V008–V014). Migration fails → the runner stops, Flyway records nothing for the failed version, fix forward; on a disposable database run the `U` scripts in reverse. Never edit an applied migration. Never enable `outOfOrder`. |

## 5. Reproducible fixture (no manual copying, no invented SHA)

`tests/fixtures/customers-db` is the snapshot of the migration owner that the API tests migrate with. It is **generated** by
`tests/pin-customers-db.mjs` from the git objects of one exact Customers DB revision (raw blobs, no line-ending conversion) and
carries `SOURCE.json` (repository, full 40-hex revision, published or not), `PROVENANCE.md` and `SHA256.json`. Every build verifies
the manifest and the recorded revision offline (`tests/check-quality.mjs`); `node tests/pin-customers-db.mjs --check` recomputes the
fixture from the recorded revision and compares byte by byte.

Procedure:

1. **While the Customers DB PR is open**: pin to the head SHA of the pushed DB branch,
   `node tests/pin-customers-db.mjs <that SHA>`. The tool refuses a SHA that no remote branch contains (use `--allow-unpublished`
   only before the first push; it is then recorded as UNPUBLISHED). The API PR **declares this dependency and stays in draft**.
2. **After the DB PR is merged**: `git -C ../synkro-customers-db fetch`, then
   `node tests/pin-customers-db.mjs <merge commit on origin/develop>`. The tool must report *"Migration bytes are identical to the
   previous pin"*; if they differ it refuses without `--accept-changes` and the differences must be reviewed first. Commit the
   re-pin (`chore(customers): re-pin the Customers DB fixture to <short sha>`), run `--check` and `node tests/verify.mjs`, then mark
   the PR ready.
3. A DB review that changes migration bytes means a new pin and a new full verification, never a hand edit.

Current state: the fixture is pinned to the local, **unpublished** Customers DB commit recorded in `SOURCE.json`. It must be re-pinned
to the pushed SHA (identical when the same commit is pushed) before the API PR leaves draft.
