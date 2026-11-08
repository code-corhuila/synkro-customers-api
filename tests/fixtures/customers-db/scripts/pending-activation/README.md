# Pending activation: contacts CHECK (HU-CLI-05)

`require-contacts-check.sql` and its rollback are **prepared and tested but not active**. They are outside every Flyway
location, so no deployment applies them. Status: **PENDING ACTIVATION — the database constraint is NOT applied**.

## Why it is not a migration now

Decision recorded for HU-CLI-05 (`Refs code-corhuila/synkro-docs#304`): *expand now, CHECK later*. With the CHECK in place
the previous API (which accepts customers without contacts or with a non-ten-digit phone) answers `500 INTERNAL_ERROR` on
those writes, which would make the previous API a non-rollback target and the DB merge order a hard constraint.
Cost accepted: until activation, the **API is the only enforcement point** for contacts.

## Authorised writers of `customers_schema`

- `customers_app` (member of `customers_writer`): used only by `synkro-customers-api`. Every ordinary create, replace,
  deactivate and reactivate goes through the API (validation, authorisation, idempotency, audit).
- The Flyway administrative login: migrations and owner-approved repair scripts, run from a protected administrative session.
- No other domain has privileges on `customers_schema` (Workflow, Auth and Products tests assert that they are denied).
  Sales stores only the customer UUID.

## Conditions for activation (all required)

1. The new API runs on **every** instance, so nothing can write contactless customers any more.
2. `scripts/contact-inventory.sql` was run in every environment and the result is complete, with no invalid contacts for
   registrations that the constraint would cover.
3. Historical rows are repaired with **real** information under an owner-approved, backed-up script; nothing is fabricated.
4. An explicit, recorded decision about rollback compatibility: after activation the previous API can no longer create
   contactless customers.

## How to activate

In a later pull request (the single authorised exception to one PR per repository), copy the file to the **next available**
migration number, in `01_ddl/04_alter/`, with its `U` rollback in `05_rollbacks/01_ddl/04_alter/`, and verify again
(`node --test tests/`, and the compatibility matrix of the API). Do not reserve a number now and do not enable
`outOfOrder`.

## Verification today

`tests/integration.test.mjs` (CONT-07) migrates a disposable database, applies this script with the administrative login and
proves: historical rows stay operable, new invalid contacts are rejected with `23514`, valid ones are accepted, and the
rollback script removes the constraint.
