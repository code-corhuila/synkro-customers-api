-- Audit is append-only for the application login: SELECT and INSERT, never UPDATE/DELETE/TRUNCATE.
REVOKE ALL ON customers_schema.customer_audit FROM PUBLIC, customers_reader, customers_writer, customers_app;
GRANT SELECT ON customers_schema.customer_audit TO customers_reader;
GRANT SELECT, INSERT ON customers_schema.customer_audit TO customers_writer;
REVOKE ALL ON FUNCTION customers_schema.fold_search_text(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION customers_schema.fold_search_text(text) TO customers_reader, customers_writer;
