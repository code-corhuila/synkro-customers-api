-- Never grant on ALL TABLES: the Flyway history must remain private.
REVOKE ALL ON SCHEMA customers_schema FROM PUBLIC, customers_reader, customers_writer, customers_app;
REVOKE ALL ON customers_schema.customer, customers_schema.idempotency_key
    FROM PUBLIC, customers_reader, customers_writer, customers_app;
REVOKE ALL ON customers_schema.flyway_schema_history
    FROM PUBLIC, customers_reader, customers_writer, customers_app;

GRANT USAGE ON SCHEMA customers_schema TO customers_reader, customers_writer;
GRANT SELECT ON customers_schema.customer, customers_schema.idempotency_key TO customers_reader;
GRANT SELECT, INSERT, UPDATE ON customers_schema.customer, customers_schema.idempotency_key TO customers_writer;
-- Infrastructure owns this login and its password. Fail if it is absent.
GRANT customers_writer TO customers_app;
