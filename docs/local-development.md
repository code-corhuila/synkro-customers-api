# Local development

Detailed procedures and delivery notes moved from the repository README at
[source 3fabaa5](https://github.com/code-corhuila/synkro-customers-api/blob/3fabaa5d1ddc8f0e32d462fa55864fe90f0078b3/README.md).
Recorded execution results retain their original scope; moving them does not
constitute a new execution or certify a different revision.

Run commands from the repository root unless a step explicitly changes directory.

## Running locally

HU-CLI-03 implements the approved Customers contract from
synkro-docs@`61056c93e6e0783a9199bf56778e9494739bc458`. PostgreSQL must already
contain the Customers DB migrations and the restricted `customers_app` login.
Infrastructure owns PostgreSQL, credentials, certificates and the platform network;
Customers DB owns schema/migrations/grants. This API never migrates or creates schema.

Supply the settings documented in [.env.example](../.env.example): a restricted JDBC
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
