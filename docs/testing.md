# Testing

Detailed procedures and delivery notes moved from the repository README at
[source 3fabaa5](https://github.com/code-corhuila/synkro-customers-api/blob/3fabaa5d1ddc8f0e32d462fa55864fe90f0078b3/README.md).
Recorded execution results retain their original scope; moving them does not
constitute a new execution or certify a different revision.

Run commands from the repository root unless a step explicitly changes directory.

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
