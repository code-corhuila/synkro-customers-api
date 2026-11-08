# Immutable test snapshot

Source: code-corhuila/synkro-customers-db.

- V001–V007, `02_dml`, `04_tcl`, `scripts` and `flyway.toml` come from `a23ba04f94ff0f150c5bafd9801cc0c6d9e98bd7` (merged PR #3), byte-for-byte from the GitHub archive.
- V008–V013 (HU-CLI-05: search folding function, `customer_audit`, contact CHECK, audit grants) are copied byte-for-byte from `c5ee3e9` of the Customers DB branch `feat/customer-contacts-search-reactivation`. **That branch is not merged yet**: once the Customers DB pull request is merged into `develop`, re-pin these six files (and `SHA256.json`) to the merge commit before this API pull request is merged. The files must not change in the process.

This fixture is test-only, outside the API artifacts and runtime image; Customers DB remains migration owner. Do not change copied migrations to accommodate tests.
