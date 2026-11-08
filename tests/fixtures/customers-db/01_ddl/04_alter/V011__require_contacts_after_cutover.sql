-- HU-CLI-05: email, phone and address are mandatory for every customer registered after this migration.
--
-- Rows registered before the cut-over are exempt on purpose: a plain CHECK evaluates the whole new row, so it
-- would also reject deactivating or reactivating a historical customer whose contacts are incomplete. Nothing is
-- repaired, fabricated or deleted here. The API validates every replacement (PUT) regardless of the row's age; the
-- exemption is removed by a later migration only after the owner-approved repair leaves no incomplete row
-- (docs/hu-cli-05-contacts-migration-policy.md, scripts/contact-inventory.sql).
--
-- The database check is deliberately no stricter than the API rules, so it can only reject what the API already rejects.
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
