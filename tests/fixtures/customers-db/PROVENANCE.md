# Immutable test snapshot

Source: `code-corhuila/synkro-customers-db`, revision `a5aebc5dfb2408ce3a2550f604d135bceacf4207` (published: contained in origin/main).

Produced by `node tests/pin-customers-db.mjs <revision>` from the git objects of that revision (raw blobs, no line-ending
conversion); `node tests/pin-customers-db.mjs --check` recomputes it, and `tests/check-quality.mjs` verifies `SHA256.json`
offline on every build. Exported paths: `01_ddl`, `02_dml`, `03_dcl`, `04_tcl`, `flyway.toml`, `scripts/migrate.sh`, `scripts/pending-activation`.

Never copy files by hand and never invent a future SHA: when the Customers DB pull request is merged, run the tool with the
**merge commit** on `develop`; the migration bytes must be identical (the tool says so, and refuses silent changes).

This fixture is test-only, outside the API artifacts and runtime image; Customers DB remains the migration owner.
