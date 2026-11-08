-- PENDING ACTIVATION (not a Flyway migration): reverts require-contacts-check.sql. Disposable instances only; in a shared
-- environment prefer a corrective migration.
ALTER TABLE customers_schema.customer DROP CONSTRAINT chk_customer_contacts_required;
