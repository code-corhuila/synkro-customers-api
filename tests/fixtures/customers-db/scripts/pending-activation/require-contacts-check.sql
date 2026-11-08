-- PENDING ACTIVATION -- this file is NOT a Flyway migration and is NOT applied by any runner or deployment.
-- It lives outside every Flyway location (flyway.toml: 01_ddl, 02_dml, 03_dcl, 04_tcl) on purpose.
--
-- HU-CLI-05 decision (expand now, CHECK later): the database does not yet require contacts, because the PREVIOUS API
-- still accepts customers without contacts and would answer 500 on every such write if this CHECK existed
-- (tests/compat-matrix.mjs in synkro-customers-api measured it). The current API validates email, phone and address on every
-- POST/PUT and is, today, the only writer of customers_schema (scripts/pending-activation/README.md).
--
-- Activation conditions (all of them; see README.md): new API on every instance, a complete inventory with no invalid
-- contacts, owner-approved repair of historical data with real information, and an explicit decision about rollback
-- compatibility. Activation means copying this file to the NEXT AVAILABLE migration number in a later pull request and
-- verifying it again; no number is reserved for it now.
--
-- Rows registered before the cut-over are exempt on purpose: a plain CHECK evaluates the whole new row, so it would also
-- reject deactivating or reactivating a historical customer whose contacts are incomplete. Nothing is repaired, fabricated
-- or deleted here. The database check is deliberately no stricter than the API rules, so it can only reject what the API
-- already rejects.
DO $contacts$
DECLARE
    cutover timestamptz := clock_timestamp();
BEGIN
    EXECUTE format($check$
        ALTER TABLE customers_schema.customer
        ADD CONSTRAINT chk_customer_contacts_required CHECK (
            registration_date < %L::timestamptz
            OR (
                email IS NOT NULL AND phone IS NOT NULL AND address IS NOT NULL
                AND email ~ '^[^[:space:]@]+@[^[:space:]@]+[.][^[:space:]@]+$'
                AND phone ~ '^[0-9]{10}$'
                AND address ~ '[^[:space:]]'
            )
        )$check$, cutover);
END
$contacts$;
