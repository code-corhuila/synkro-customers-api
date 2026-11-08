package co.edu.corhuila.synkro.customers.app;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** HU-CLI-05 Scenarios 1-3 and 6: required contacts, no partial writes, historical customers. Real HTTP + PostgreSQL. */
class CustomerContactsHttpTest extends HttpTestSupport {
    static final Map<String, Object> VALID = Map.of("name", "Ana", "email", "ana@example.test", "phone", "3001234567", "address", "Calle 1");

    String payload(String doc, Map<String, Object> overrides) throws Exception {
        var m = new LinkedHashMap<String, Object>(VALID); m.put("identityDocument", doc); m.putAll(overrides); return JSON.writeValueAsString(m);
    }
    String withoutKey(String doc, String key) throws Exception {
        var m = new LinkedHashMap<String, Object>(VALID); m.put("identityDocument", doc); m.remove(key); return JSON.writeValueAsString(m);
    }

    @Test void validCreationAndReplacementPersistTheExactValues() throws Exception {
        String doc = unique(); String email = "Ana.Perez+tag@Example.TEST", address = "  Carrera 7 # 45-10, Bogotá  ";
        var created = request("POST", "/api/v1/customers", seller(), payload(doc, Map.of("email", email, "phone", "0123456789", "address", address)), unique());
        assertThat(created.statusCode()).isEqualTo(201);
        var c = json(created);
        assertThat(c.get("email").asText()).isEqualTo(email);          // not lower-cased
        assertThat(c.get("phone").isTextual()).isTrue();               // text, leading zero kept
        assertThat(c.get("phone").asText()).isEqualTo("0123456789");
        assertThat(c.get("address").asText()).isEqualTo(address);      // not trimmed
        String path = "/api/v1/customers/" + id(created);
        var replaced = request("PUT", path, admin(), payload(doc, Map.of("email", "eva@example.test", "phone", "3109876543", "address", "Calle 9")), null);
        assertThat(replaced.statusCode()).isEqualTo(200);
        assertThat(json(replaced).get("phone").asText()).isEqualTo("3109876543");
        assertThat(json(request("GET", path, seller(), null, null)).get("email").asText()).isEqualTo("eva@example.test");
        assertThat(id(replaced)).isEqualTo(id(created));
    }

    @Test void missingBlankAndInvalidContactsAreRejectedWithTheFieldAndWriteNothing() throws Exception {
        record Bad(String field, Object value) {}
        var bad = new ArrayList<Bad>();
        for (String field : List.of("email", "phone", "address")) {
            bad.add(new Bad(field, ""));  bad.add(new Bad(field, "   ")); bad.add(new Bad(field, " ")); bad.add(new Bad(field, null));
        }
        for (String email : List.of("plain", "a@b", "a b@example.test", "a@@example.test", "a@example..test", "ñ@example.test", "a@example.test ", "x".repeat(250) + "@e.co"))
            bad.add(new Bad("email", email));
        for (String phone : List.of("123456789", "12345678901", "30012345a7", "+573001234567", "300.1234567", "300 123 4567", "3001234567.0", "٣٠٠١٢٣٤٥٦٧"))
            bad.add(new Bad("phone", phone));
        bad.add(new Bad("address", "x".repeat(256)));
        for (Bad b : bad) {
            String doc = unique(), key = unique(); var overrides = new HashMap<String, Object>(); overrides.put(b.field(), b.value());
            var rejected = request("POST", "/api/v1/customers", admin(), payload(doc, overrides), key);
            assertThat(rejected.statusCode()).as("%s=%s", b.field(), b.value()).isEqualTo(400);
            assertThat(json(rejected).get("error").asText()).isEqualTo("VALIDATION_ERROR");
            assertThat(json(rejected).get("details").findValuesAsText("field")).containsExactly(b.field());
            assertThat(json(rejected).get("traceId").asText()).isEqualTo("test-correlation");
            assertThat(count("customer", "identity_document", doc)).as("no customer written").isZero();
            assertThat(count("idempotency_key", "key", key)).as("no idempotency key written").isZero();
        }
        for (String key : List.of("email", "phone", "address")) {   // an absent property is the same as null
            String doc = unique();
            var rejected = request("POST", "/api/v1/customers", admin(), withoutKey(doc, key), unique());
            assertThat(rejected.statusCode()).isEqualTo(400);
            assertThat(json(rejected).get("details").findValuesAsText("field")).containsExactly(key);
            assertThat(count("customer", "identity_document", doc)).isZero();
        }
    }

    @Test void everyInvalidFieldIsReportedTogetherSoThePortalCanMarkThemAll() throws Exception {
        var r = request("POST", "/api/v1/customers", admin(), "{\"name\":\"\",\"identityDocument\":\"x\",\"email\":\"nope\",\"phone\":\"12\"}", unique());
        assertThat(r.statusCode()).isEqualTo(400);
        var details = json(r).get("details");
        assertThat(details.findValuesAsText("field")).containsExactly("name", "email", "phone", "address");
        assertThat(details.findValuesAsText("message")).containsExactly("required", "invalid_format", "invalid_format", "required");
        assertThat(r.body()).doesNotContain("nope");   // the rejected value is never echoed back
    }

    @Test void aRejectedReplacementLeavesTheStoredCustomerUntouched() throws Exception {
        String doc = unique(); var created = create(doc); String path = "/api/v1/customers/" + id(created);
        for (Map<String, Object> change : List.of(Map.<String, Object>of("phone", "123"), Map.<String, Object>of("email", ""), Map.<String, Object>of("address", " ")))
            assertThat(request("PUT", path, admin(), payload(doc, change), null).statusCode()).isEqualTo(400);
        assertThat(json(request("GET", path, admin(), null, null))).isEqualTo(json(created));
        assertThat(request("PUT", path, admin(), "{\"name\":\"Solo nombre\",\"identityDocument\":\"" + doc + "\"}", null).statusCode()).isEqualTo(400);
    }

    @Test void validationPrecedesIdempotentReplayAndNeverConsumesTheKey() throws Exception {
        String doc = unique(), key = unique();
        assertThat(request("POST", "/api/v1/customers", admin(), payload(doc, Map.of("phone", "1")), key).statusCode()).isEqualTo(400);
        assertThat(count("idempotency_key", "key", key)).isZero();
        var first = request("POST", "/api/v1/customers", admin(), payload(doc, Map.of()), key);          // the same key can still be used for a valid body
        assertThat(first.statusCode()).isEqualTo(201);
        var replay = request("POST", "/api/v1/customers", admin(), payload(doc, Map.of()), key);
        assertThat(replay.statusCode()).isEqualTo(200); assertThat(json(replay)).isEqualTo(json(first));
        assertThat(request("POST", "/api/v1/customers", admin(), payload(doc, Map.of("email", "bad")), key).statusCode()).isEqualTo(400);
        assertThat(count("customer", "identity_document", doc)).isEqualTo(1);
    }

    @Test void documentsStayUniqueAcrossActiveAndInactiveCustomersWithCompleteContacts() throws Exception {
        String doc = unique(); var owner = create(doc);
        assertThat(create(doc).statusCode()).isEqualTo(422);
        deactivate(id(owner));
        var duplicate = create(doc);
        assertThat(duplicate.statusCode()).isEqualTo(422);
        assertThat(json(duplicate).get("details").findValuesAsText("field")).containsExactly("identityDocument");
        assertThat(count("customer", "identity_document", doc)).isEqualTo(1);
    }

    @Test void bothHumanRolesKeepTheirWritePermissionsAndOthersStayDenied() throws Exception {
        for (String role : List.of(admin(), seller())) {
            var created = request("POST", "/api/v1/customers", role, payload(unique(), Map.of()), unique());
            assertThat(created.statusCode()).isEqualTo(201);
            assertThat(request("PUT", "/api/v1/customers/" + id(created), role, payload(unique(), Map.of()), null).statusCode()).isEqualTo(200);
        }
        for (String denied : List.of(inventory(), workflow()))
            assertThat(request("POST", "/api/v1/customers", denied, payload(unique(), Map.of()), unique()).statusCode()).isEqualTo(403);
    }

    // ---- historical customers: stored before the contacts became mandatory ---------------------------------------------
    @Test void aHistoricalCustomerWithMissingContactsStaysReadableAndKeepsItsIdentity() throws Exception {
        String doc = unique(), legacy = legacyCustomer("Cliente histórico", doc, true); String path = "/api/v1/customers/" + legacy;
        var read = request("GET", path, workflow(), null, null);                // Workflow's validate-customer read
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(json(read).get("customerId").asText()).isEqualTo(legacy);
        assertThat(json(read).get("active").asBoolean()).isTrue();
        assertThat(json(read).get("email").isNull()).isTrue();
        assertThat(json(request("GET", "/api/v1/customers?identityDocument=" + doc, admin(), null, null)).get("data").size()).isEqualTo(1);
        // ...but it cannot be replaced with incomplete data: the repair happens when someone completes it.
        assertThat(request("PUT", path, admin(), "{\"name\":\"Renombrado\",\"identityDocument\":\"" + doc + "\"}", null).statusCode()).isEqualTo(400);
        var repaired = request("PUT", path, admin(), payload(doc, Map.of("name", "Completado")), null);
        assertThat(repaired.statusCode()).isEqualTo(200);
        assertThat(id(repaired)).isEqualTo(legacy);                            // same UUID
        assertThat(json(repaired).get("phone").asText()).isEqualTo("3001234567");
    }

    @Test void aHistoricalCustomerCanStillBeDeactivatedWithoutFabricatingContacts() throws Exception {
        String doc = unique(), legacy = legacyCustomer("Cliente histórico", doc, true);
        var inactive = request("DELETE", "/api/v1/customers/" + legacy, seller(), null, null);
        assertThat(inactive.statusCode()).isEqualTo(200);
        assertThat(json(inactive).get("active").asBoolean()).isFalse();
        assertThat(json(inactive).get("email").isNull()).isTrue();
        assertThat(json(inactive).get("phone").isNull()).isTrue();
        assertThat(json(inactive).get("address").isNull()).isTrue();
    }
}
