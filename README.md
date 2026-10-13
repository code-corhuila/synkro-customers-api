# synkro-customers-api

> customers bounded context: service API

Part of the **SynkroTech SAS Sales Management System** — organization `code-corhuila`.
Governance and documentation live in [`synkro-docs`](https://github.com/code-corhuila/synkro-docs).

## Branching

Three permanent branches. **None of them accepts a direct commit** — you enter through a child
branch and leave through a Pull Request.

```
develop  <--PR--  feat/... fix/... chore/...
qa       <--PR--  qa/...
main     <--PR--  release/...  hotfix/...
```

Promotion happens **by re-application** (`git cherry-pick -x`), never by merging one permanent
branch into another: `merge develop -> qa` and `merge qa -> main` do not exist in this model.

`main` requires **1 approval from `ariel5253`**. On `develop` and `qa` the team sets its own review
rule.

Full policy: `00-governance/branching-policy.md` in `synkro-docs`.

## Running locally

HU-CLI-03 implements the approved Customers contract from
synkro-docs@`61056c93e6e0783a9199bf56778e9494739bc458`. PostgreSQL must already
contain the Customers DB migrations and the restricted `customers_app` login.
Infrastructure owns PostgreSQL, credentials, certificates and the platform network;
Customers DB owns schema/migrations/grants. This API never migrates or creates schema.

Supply the settings documented in [.env.example](.env.example): a restricted JDBC
URL, `customers_app` password, and Auth's RSA public key. A configured password/key
file takes precedence; an invalid file stops startup without environment fallback.
Never supply the Auth private key or a database administrator credential.

```bash
mvn install -DskipTests
mvn -pl customers-app spring-boot:run
```

The first command installs `customers-core` and `customers-adapters` into the local Maven repository
so `customers-app` can resolve them; re-run it after changing either module. The service listens on
`:8080`. Verify it:

```bash
curl http://localhost:8080/health
```

## Running the tests

```bash
node tests/verify.mjs
node tests/check-deployment.mjs
```

Requires Node.js 22 and Docker. The command provisions exclusive PostgreSQL 16.6
resources, runs the approved Customers DB Flyway 10.20.1 runner and immutable
test snapshot, builds on Maven/Java 21, runs every test, enforces coverage/architecture,
and checks real executable restart, database disconnection, 503 health and recovery.
It cleans its labelled containers/network and temporary secrets. Existing resources
are preserved. There are no public database ports or persistent database volumes.
An optional local Maven cache is retained in ignored `.test-cache`; it contains no
test credentials. Missing `TEST_DATABASE_URL` fails the HTTP tests instead of skipping.
The fixture-only administrative credential never reaches the runtime API.
The second command builds the owning Dockerfile, checks its non-root image and
validates both internal Compose configurations with synthetic settings. It removes
only its labelled image. CI runs both commands on the checked-out SHA.

For isolated unit iteration: `mvn -pl customers-core test`. Direct `mvn verify`
requires the fixture variables and a migrated disposable database. The canonical
full verification command above supplies them. JaCoCo aggregates all modules;
thresholds remain domain 90%, application 80%, infrastructure 60%, global 80%.

## HTTP and authorization

`GET/POST /api/v1/customers`, `GET/PUT/DELETE /api/v1/customers/{id}` and public
`GET /health` follow the published OpenAPI. Human ADMIN and SALESPERSON have all
Customers operations. INVENTORY has none. Only SERVICE subject `synkro-workflow`
with `customers:read` may retrieve by ID; it cannot list or write. Local RS256
validation checks Auth's signature, expiration/issued time (30-second skew), subject,
roles, permissions and jti. Forged user headers have no authority.

Registration requires an 8–128 character Idempotency-Key: 201 and Location first,
then 200 with the same stored customer. Different valid bodies with the same key
replay the original resource; there is no body hash, expiry or owner column.
Authorization precedes replay. A transaction-scoped key lock serializes concurrency;
customer and key commit together. Database uniqueness conflicts are rolled back
before any fresh lookup. Documents stay unique across inactive customers (422).

Listing supports exact identityDocument and optional active, defaults page=1 and
limit=20 (1–100), and includes both states without active. Unknown/invalid/repeated
filters return 400. Ordering is registration_date DESC, customer_id DESC. PUT
replaces name/document/contact fields, clears omitted optional fields and preserves
customerId/registrationDate. Inactive/missing GET and PUT return 404. DELETE updates
active=false, returns 200 and leaves an already inactive row unchanged.

Errors use the shared English envelope and X-Correlation-Id/traceId. Storage failures
return a safe 500; health returns 200 or 503 with database ok/down. Logs are structured
JSON without customer bodies, documents, SQL exceptions or credentials.

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

## Joint acceptance and handoff

`node tests/joint-acceptance.mjs` is a separately evidenced local scenario requiring
the immutable participant archives described in [evidence](docs/evidence.md).
Prepare them with `node tests/prepare-joint-snapshots.mjs` (authenticated read access
to the private repositories through GitHub CLI and a tar executable are required).
It builds actual Auth, Customers, Workflow, Products and Sales Dockerfiles, uses
Auth-issued disposable credentials, real database runners and no mock participant.
Private cross-repository snapshots are not downloaded by ordinary CI.

[Design](docs/design.md), [acceptance](docs/acceptance.md),
[evidence](docs/evidence.md), [handoffs](docs/handoff.md), and
[prepared PR/review sequence](docs/pr-proposal.md) describe verification and remaining
documentary or integration limits. This delivery does not close the HU or promote
the platform. CODEOWNERS is preserved.
