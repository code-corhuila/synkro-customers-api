package co.edu.corhuila.synkro.customers.app;

import org.junit.jupiter.api.Test;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** HU-CLI-05 Scenarios 5 and 6: ADMIN-only activation, safe repetition, attribution and identity preservation. */
class CustomerReactivationHttpTest extends HttpTestSupport {
    String inactiveCustomer(String doc) throws Exception { String id = id(create(doc)); deactivate(id); return id; }

    @Test void adminReactivatesTheSameCustomerAndTheOperatorIsRecorded() throws Exception {
        String doc = unique(); var created = create(doc); String id = id(created), path = "/api/v1/customers/" + id;
        deactivate(id);
        assertThat(request("GET", path, admin(), null, null).statusCode()).isEqualTo(404);       // inactive: unavailable to sales
        var r = request("POST", path + "/activate", admin(), null, null);
        assertThat(r.statusCode()).isEqualTo(200);
        var c = json(r);
        assertThat(c.get("customerId").asText()).isEqualTo(id);                                  // same UUID
        assertThat(c.get("identityDocument").asText()).isEqualTo(doc);                           // same document
        assertThat(c.get("registrationDate")).isEqualTo(json(created).get("registrationDate"));
        assertThat(c.get("active").asBoolean()).isTrue();
        assertThat(r.headers().firstValue("X-Correlation-Id")).hasValue("test-correlation");
        assertThat(count("customer", "identity_document", doc)).isEqualTo(1);                    // no other customer was created
        assertThat(json(request("GET", path, workflow(), null, null)).get("active").asBoolean()).isTrue();   // Workflow can validate it again
        assertThat(request("PUT", path, seller(), body(doc), null).statusCode()).isEqualTo(200);             // editable again
        try (var db = db(false); var s = db.prepareStatement("SELECT action, actor_id::text, correlation_id, occurred_at FROM customers_schema.customer_audit WHERE customer_id=?::uuid")) {
            s.setString(1, id);
            try (var rs = s.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("REACTIVATED");
                assertThat(rs.getString(2)).isEqualTo(ADMIN_ID);
                assertThat(rs.getString(3)).isEqualTo("test-correlation");
                assertThat(rs.getTimestamp(4)).isAfter(Timestamp.valueOf("2026-01-01 00:00:00"));
                assertThat(rs.next()).isFalse();
            }
        }
    }

    @Test void repeatingTheTransitionIsSafeAndWritesNoSecondAuditRecord() throws Exception {
        String id = inactiveCustomer(unique()), path = "/api/v1/customers/" + id + "/activate";
        var first = request("POST", path, admin(), null, null);
        for (int i = 0; i < 3; i++) {
            var again = request("POST", path, admin(), null, null);
            assertThat(again.statusCode()).isEqualTo(200);
            assertThat(json(again)).isEqualTo(json(first));
        }
        assertThat(audits(id)).isEqualTo(1);
    }

    @Test void activatingAnAlreadyActiveCustomerChangesNothing() throws Exception {
        var created = create(unique()); String id = id(created);
        var r = request("POST", "/api/v1/customers/" + id + "/activate", admin(), null, null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json(r)).isEqualTo(json(created));
        assertThat(audits(id)).isZero();
    }

    @Test void unknownAndMalformedIdentifiersAreRejectedWithTheStandardEnvelope() throws Exception {
        var missing = request("POST", "/api/v1/customers/" + UUID.randomUUID() + "/activate", admin(), null, null);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(json(missing).get("error").asText()).isEqualTo("NOT_FOUND");
        for (String bad : List.of("not-a-uuid", "123", "00000000-0000-0000-0000-00000000000g")) {
            var r = request("POST", "/api/v1/customers/" + bad + "/activate", admin(), null, null);
            assertThat(r.statusCode()).as(bad).isEqualTo(400);
            assertThat(json(r).get("error").asText()).isEqualTo("VALIDATION_ERROR");
        }
    }

    @Test void everyoneButAHumanAdminIsDeniedDirectlyAtTheApi() throws Exception {
        String id = inactiveCustomer(unique()), path = "/api/v1/customers/" + id + "/activate";
        var denied = List.of(seller(), inventory(), workflow(), token("synkro-worker", "SERVICE", List.of("customers:read")),
                token(UUID.randomUUID().toString(), "SERVICE", List.of("customers:read", "customers:write")), token(UUID.randomUUID().toString(), "OTHER", List.of()),
                token(UUID.randomUUID().toString(), "admin", List.of()));
        for (String token : denied) {
            var r = request("POST", path, token, null, null);
            assertThat(r.statusCode()).isEqualTo(403);
            assertThat(json(r).get("error").asText()).isEqualTo("FORBIDDEN");
        }
        assertThat(request("POST", path, null, null, null).statusCode()).isEqualTo(401);
        assertThat(request("POST", path, "not-a-token", null, null).statusCode()).isEqualTo(401);
        assertThat(request("POST", path, seller(), "{\"active\":true}", null).statusCode()).isEqualTo(403);   // a body grants nothing
        assertThat(audits(id)).isZero();
        assertThat(request("GET", "/api/v1/customers/" + id, admin(), null, null).statusCode()).isEqualTo(404);   // still inactive
    }

    @Test void forgedIdentityHeadersGrantNothingAndTheAuditedActorIsTheAuthenticatedSubject() throws Exception {
        String id = inactiveCustomer(unique()), path = "/api/v1/customers/" + id + "/activate";
        var forged = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + path)).header("Authorization", "Bearer " + seller())
                .header("X-User-Role", "ADMIN").header("X-Roles", "ADMIN").header("X-User-Id", ADMIN_ID).header("X-Actor-Id", ADMIN_ID)
                .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"role\":\"ADMIN\",\"actorId\":\"" + ADMIN_ID + "\"}")).build();
        assertThat(client.send(forged, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        assertThat(audits(id)).isZero();
        String otherAdmin = UUID.randomUUID().toString();
        var allowed = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + path)).header("Authorization", "Bearer " + token(otherAdmin, "ADMIN", List.of()))
                .header("X-User-Id", ADMIN_ID).header("X-Actor-Id", ADMIN_ID).header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"actorId\":\"" + ADMIN_ID + "\"}")).build();
        assertThat(client.send(allowed, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        try (var db = db(false); var st = db.prepareStatement("SELECT actor_id::text FROM customers_schema.customer_audit WHERE customer_id=?::uuid")) {
            st.setString(1, id); try (var rs = st.executeQuery()) { assertThat(rs.next()).isTrue(); assertThat(rs.getString(1)).isEqualTo(otherAdmin); assertThat(rs.next()).isFalse(); }
        }
    }

    @Test void theOrdinaryUpdateCannotBeUsedToReactivate() throws Exception {
        String doc = unique(), id = id(create(doc)); deactivate(id);
        for (String token : List.of(admin(), seller())) {
            assertThat(request("PUT", "/api/v1/customers/" + id, token, body(doc), null).statusCode()).isEqualTo(404);
            assertThat(request("PUT", "/api/v1/customers/" + id, token, "{\"name\":\"X\",\"identityDocument\":\"" + doc + "\",\"active\":true,\"email\":\"a@example.test\",\"phone\":\"3001234567\",\"address\":\"c\"}", null).statusCode()).isEqualTo(404);
        }
        assertThat(audits(id)).isZero();
        assertThat(json(request("GET", "/api/v1/customers?identityDocument=" + doc, admin(), null, null)).at("/data/0/active").asBoolean()).isFalse();
    }

    @Test void aSecondCustomerCanNeverTakeTheDocumentOfAnInactiveOneNorOfAReactivatedOne() throws Exception {
        String doc = unique(), id = inactiveCustomer(doc);
        assertThat(create(doc).statusCode()).isEqualTo(422);
        assertThat(request("POST", "/api/v1/customers/" + id + "/activate", admin(), null, null).statusCode()).isEqualTo(200);
        assertThat(create(doc).statusCode()).isEqualTo(422);
        String other = id(create(unique()));
        assertThat(request("PUT", "/api/v1/customers/" + other, admin(), body(doc), null).statusCode()).isEqualTo(422);
        assertThat(count("customer", "identity_document", doc)).isEqualTo(1);
    }

    @Test void concurrentActivationsFlipTheRowOnceAndAuditOnce() throws Exception {
        String id = inactiveCustomer(unique()), path = "/api/v1/customers/" + id + "/activate", token = admin();
        var pool = Executors.newFixedThreadPool(8);
        try {
            var jobs = new ArrayList<Callable<Integer>>();
            for (int i = 0; i < 8; i++) jobs.add(() -> request("POST", path, token, null, null).statusCode());
            for (var f : pool.invokeAll(jobs)) assertThat(f.get()).isEqualTo(200);
        } finally { pool.shutdownNow(); }
        assertThat(audits(id)).isEqualTo(1);
        assertThat(json(request("GET", "/api/v1/customers/" + id, admin(), null, null)).get("active").asBoolean()).isTrue();
    }

    @Test void aFailedAuditRollsTheActivationBack() throws Exception {
        String id = inactiveCustomer(unique()), constraint = "test_audit_failure_" + unique().substring(0, 8).replace('-', '_');
        admin("ALTER TABLE customers_schema.customer_audit ADD CONSTRAINT " + constraint + " CHECK (correlation_id IS DISTINCT FROM 'kaput-trace')");
        try {
            var r = request("POST", "/api/v1/customers/" + id + "/activate", admin(), null, null, "kaput-trace");
            assertThat(r.statusCode()).isEqualTo(500);
            assertThat(json(r).get("error").asText()).isEqualTo("INTERNAL_ERROR");
            assertThat(r.body()).doesNotContain("customer_audit", "constraint", "SQL", "postgres", "Exception");
            assertThat(audits(id)).isZero();
            assertThat(request("GET", "/api/v1/customers/" + id, admin(), null, null).statusCode()).isEqualTo(404);   // still inactive
        } finally { admin("ALTER TABLE customers_schema.customer_audit DROP CONSTRAINT " + constraint); }
        assertThat(request("POST", "/api/v1/customers/" + id + "/activate", admin(), null, null).statusCode()).isEqualTo(200);   // safe retry
    }

    @Test void aHugeOrMissingCorrelationIdNeverBlocksTheActivation() throws Exception {
        String id = inactiveCustomer(unique());
        assertThat(request("POST", "/api/v1/customers/" + id + "/activate", admin(), null, null, "t".repeat(300)).statusCode()).isEqualTo(200);
        String other = inactiveCustomer(unique());
        assertThat(request("POST", "/api/v1/customers/" + other + "/activate", admin(), null, null, null).statusCode()).isEqualTo(200);
        assertThat(audits(id)).isEqualTo(1); assertThat(audits(other)).isEqualTo(1);
        try (var db = db(false); var s = db.prepareStatement("SELECT char_length(correlation_id) FROM customers_schema.customer_audit WHERE customer_id=?::uuid")) {
            s.setString(1, id); try (var rs = s.executeQuery()) { rs.next(); assertThat(rs.getInt(1)).isEqualTo(128); }
        }
    }

    @Test void aHistoricalInactiveCustomerIsReactivatedWithoutInventingContacts() throws Exception {
        String doc = unique(), legacy = legacyCustomer("Histórico inactivo", doc, false);
        var r = request("POST", "/api/v1/customers/" + legacy + "/activate", admin(), null, null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json(r).get("active").asBoolean()).isTrue();
        assertThat(json(r).get("email").isNull()).isTrue();
        assertThat(json(r).get("phone").isNull()).isTrue();
        assertThat(audits(legacy)).isEqualTo(1);
    }
}
