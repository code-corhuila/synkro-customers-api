DO $roles$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'customers_reader') THEN
        CREATE ROLE customers_reader NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'customers_writer') THEN
        CREATE ROLE customers_writer NOLOGIN;
    END IF;
    IF EXISTS (
        SELECT FROM pg_catalog.pg_roles
        WHERE rolname IN ('customers_reader', 'customers_writer')
          AND (rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)
    ) THEN
        RAISE EXCEPTION 'Unsafe existing Customers role; reconcile role ownership and privileges';
    END IF;
END
$roles$;
