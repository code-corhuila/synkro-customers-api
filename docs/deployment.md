# Deployment

Detailed procedures and delivery notes moved from the repository README at
[source 3fabaa5](https://github.com/code-corhuila/synkro-customers-api/blob/3fabaa5d1ddc8f0e32d462fa55864fe90f0078b3/README.md).
Recorded execution results retain their original scope; moving them does not
constitute a new execution or certify a different revision.

Run commands from the repository root unless a step explicitly changes directory.

## Deployment and operations

The owning `deploy/Dockerfile` remains a multistage Java 21 build with a non-root
runtime. `deploy/compose.yml` exposes 8080 only on the external platform network.
For protected host files, add `deploy/compose.secrets.yml` and configure absolute
`CUSTOMERS_APP_PASSWORD_FILE` and `JWT_PUBLIC_KEY_HOST_FILE` paths outside the repo.
Ensure mounts are readable by the image's synkro user. The standard environment mode
uses `SPRING_DATASOURCE_PASSWORD` and `JWT_PUBLIC_KEY`; the file override is optional.

Pool=10, acquire=5s, JDBC connect/login/socket=3/5/10s, statement=5s,
transaction=10s, graceful shutdown=20s. The URL may reduce JDBC timeouts and select
TLS sslmode; it cannot select another schema/user or remove bounds. Infrastructure
mounts PostgreSQL trust certificates for verify-ca/verify-full. No automatic retries
are applied to an uncertain create commit: callers retry with the same key.

Before deployment, run the DB owner's migrate/validate and verify restricted grants.
Then start the API and check `/health`. Diagnose failures through correlation and
protected operational access, without enabling SQL/payload logging. Restore storage
before retrying customer operations. Schema recovery/backups stay with the owners.
