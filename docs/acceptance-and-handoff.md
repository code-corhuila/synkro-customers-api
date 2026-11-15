# Acceptance and handoff

Detailed procedures and delivery notes moved from the repository README at
[source 3fabaa5](https://github.com/code-corhuila/synkro-customers-api/blob/3fabaa5d1ddc8f0e32d462fa55864fe90f0078b3/README.md).
Recorded execution results retain their original scope; moving them does not
constitute a new execution or certify a different revision.

Run commands from the repository root unless a step explicitly changes directory.

## Joint acceptance and handoff

`node tests/joint-acceptance.mjs` is a separately evidenced local scenario requiring
the immutable participant archives described in [evidence](evidence.md).
Prepare them with `node tests/prepare-joint-snapshots.mjs` (authenticated read access
to the private repositories through GitHub CLI and a tar executable are required).
It builds actual Auth, Customers, Workflow, Products and Sales Dockerfiles, uses
Auth-issued disposable credentials, real database runners and no mock participant.
Private cross-repository snapshots are not downloaded by ordinary CI.

[Design](design.md), [acceptance](acceptance.md),
[evidence](evidence.md), [handoffs](handoff.md), and
[prepared PR/review sequence](pr-proposal.md) describe verification and remaining
documentary or integration limits. This delivery does not close the HU or promote
the platform. CODEOWNERS is preserved.

### Consumer-driven QA contracts

Run `CUSTOMERS_VERIFY_PACT=1 node tests/verify.mjs` with Node 22 and Docker. Generated consumer artifacts, their published full commit revisions and UTF-8/LF SHA-256 hashes are recorded in `tests/pact/provenance.json`; verification rejects changed artifacts. Pact JS 17.1.4 is isolated from application dependencies. The owned Node runner installs system CA certificates required by Pact's HTTP verifier. A deliberately incompatible HTTP provider must receive the requests and fail verification before the real API/database verification can pass. State setup affects only the launcher's disposable database. Real verification failures propagate a nonzero exit and the launcher removes only its own labelled resources. No broker publication or hosted-green result is implied. This gate complements OpenAPI conformance and does not replace it.
