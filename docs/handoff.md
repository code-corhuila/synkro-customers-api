# Coordinated handoffs (prepared, not sent)

## Workflow / HU-WKF-03, WKF-16

Use Customers GET `/api/v1/customers/{id}` with Auth-issued SERVICE subject
`synkro-workflow`, roles `[SERVICE]`, permission `customers:read`. Customers checks
signature/exp/iat/sub/jti and claim shapes locally. SERVICE cannot list or write;
another service does not gain access by supplying the permission string.
Response fields include customerId, name, identityDocument, nullable contacts,
registrationDate and active. Inactive/missing returns contractual 404 NOT_FOUND.
No registrationDate/registeredAt substitution is permitted.

Use the immutable Customers participant SHA and joint evidence in evidence.md.
Workflow's owner retains official WKF-16 updates; this packet supplies interoperable
evidence and does not update another issue, pin or repository.

## Sales Portal / HU-VEN-12 (synkro-docs#246)

The exact document lookup is `GET /api/v1/customers?identityDocument=...` with the
human ADMIN/SALESPERSON token. Without active the list includes inactive matches,
so the portal can report that an existing customer cannot be used for a new sale.
Use data/meta, page=1 and default limit=20. Do not use SERVICE credentials in a browser.
Registration needs a caller-generated Idempotency-Key; retain it for a retry after
an uncertain result. Document duplicates return 422 with identityDocument details.

Documentary correction for #247 and coordination references: replace HU-SALES-12
with the actual **HU-VEN-12 / #246**. Do not automatically edit the issue.

## Infrastructure / HU-INF-02

Keep PostgreSQL, volumes, LOGIN credentials and trust certificates under infrastructure
ownership. Customers DB migrate/validate owns customers_schema and grants; this API
connects only as customers_app. Verify role isolation after all domain runners.
The API image has no migration runner and no administrator credential.

Include deploy/compose.yml on the external platform network, no host DB/API ports.
Supply `.env.example` settings through protected environment or the optional secret
override. Files must be readable by the non-root synkro runtime user. Auth private
key goes only to Auth; Customers receives its X.509 public key. A configured invalid
file fails instead of falling back. Pool and all timeouts are bounded and documented.

Adopt a candidate SHA only after owner approval; no dependency pins were changed.
The isolated fixture is not a test of the final root composition or production volumes.

## Prepared synkro-docs changes (read-only repository)

1. Publish the already-approved CLI-API-16/17 wording from issue-247-correction.md
   in #247; its currently retrieved body ends mid-sentence. The user approved the
   full wording on 2026-10-12. Keep HU-CLI-03 In Progress until review/owner sign-off.
2. Link requirements → HU-CLI-03/#247 → LifecycleHttpTest, CustomerServiceTest,
   CustomerTest, ConfigurationTest, runtime.mjs, joint-acceptance.mjs and
   check-deployment.mjs → CI SHA.
3. Update service documentation/runbook and deployment/local setup with restricted
   credentials, public key/file precedence, bounded JDBC/TLS settings, 503 health,
   registrationDate and approved Customers DB prerequisite.
4. Correct the DB handoff's registeredAt example to the OpenAPI registrationDate.
5. Correct HU-SALES-12 to HU-VEN-12/#246 and coordinate WKF-16 with its owner.
6. Record the actual participant SHAs and functional/security limits. Products'
   approved revision still uses bearer-shape authentication; integration success
   cannot close platform security or substitute its owner's RS256 delivery.
7. Preserve accepted ADRs and existing estimates; no source contract/schema change
   is required for this implementation. Do not mark full platform acceptance Done.
