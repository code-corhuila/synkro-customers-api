-- HU-CLI-05: stored, pre-folded copies of the name and the identity document that the customer search compares.
--
-- Without them the search evaluates fold_search_text on every row of every query (about 16 microseconds per row: 320 ms at
-- 20 000 customers, 1.5 s at 100 000 in docs/hu-cli-05-search-benchmark.md). Generated columns are computed by the
-- database on INSERT/UPDATE, so no application (including the previous API, which never mentions them) has to know about
-- them, and they can never disagree with name / identity_document.
--
-- document_search uses the "C" collation so a plain btree supports exact and prefix (range) matching; name_search keeps the
-- default collation so the order of results is the same one the previous query produced.
--
-- Operational note: adding a STORED generated column rewrites the table under an ACCESS EXCLUSIVE lock (about 0.3 s per
-- 20 000 rows measured on a development machine). Apply it in a quiet window (docs/hu-cli-05-contacts-migration-policy.md).
-- If fold_search_text is ever redefined, these columns and their index must be rebuilt in a new migration.
ALTER TABLE customers_schema.customer
    ADD COLUMN name_search text GENERATED ALWAYS AS (customers_schema.fold_search_text(name)) STORED,
    ADD COLUMN document_search text COLLATE "C" GENERATED ALWAYS AS (customers_schema.fold_search_text(identity_document)) STORED;
