# Http and authorization

Detailed procedures and delivery notes moved from the repository README at
[source 3fabaa5](https://github.com/code-corhuila/synkro-customers-api/blob/3fabaa5d1ddc8f0e32d462fa55864fe90f0078b3/README.md).
Recorded execution results retain their original scope; moving them does not
constitute a new execution or certify a different revision.

Run commands from the repository root unless a step explicitly changes directory.

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

HU-CLI-05 (contract *proposal* until the Sales owner agrees it, see
[docs/hu-cli-05-contacts-search-reactivation.md](hu-cli-05-contacts-search-reactivation.md)):
email, phone (exactly ten ASCII digits, text) and address are mandatory on create and on
the replacement PUT (400 with one `details` entry per field; nothing is written).
`POST /api/v1/customers/search` takes the text in the **body** (never URL or logs),
returns at most 5 suggestions (`hasMore` signals more), is case/accent-insensitive,
orders exact document first and answers only ADMIN/SALESPERSON; inactive customers are
searched only on explicit `INACTIVE` by ADMIN. `POST /api/v1/customers/{id}/activate`
is the only way back from inactive: ADMIN only (route and use case), idempotent, audited
once in `customer_audit` in the same transaction; PUT still answers 404 for inactive.
Historical customers with missing contacts stay readable, deactivatable and
reactivatable and keep their UUID; they must be completed on their next PUT.
It needs Customers DB migrations V008–V014 (additive and compatible with the previous API; the test fixture is pinned to one
exact DB revision with `node tests/pin-customers-db.mjs`). The database does **not** enforce contacts yet: the CHECK is prepared in
Customers DB and activated later; this API is the enforcement point until then.
Compatibility matrix, deployment order and fixture procedure:
[docs/hu-cli-05-deployment-compatibility.md](hu-cli-05-deployment-compatibility.md) (`node tests/compat-matrix.mjs`).

Errors use the shared English envelope and X-Correlation-Id/traceId. Storage failures
return a safe 500; health returns 200 or 503 with database ok/down. Logs are structured
JSON without customer bodies, documents, SQL exceptions or credentials.
