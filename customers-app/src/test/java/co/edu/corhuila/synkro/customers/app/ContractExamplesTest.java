package co.edu.corhuila.synkro.customers.app;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/**
 * The JSON examples published for HU-CLI-05 (src/test/resources/contracts, quoted in docs/hu-cli-05-*.md) are replayed
 * against the real HTTP server and PostgreSQL: the documented shape is the shape the API really returns. Values that
 * are generated per run (identifiers, trace ids, dates) are compared by type/format, everything else exactly.
 */
class ContractExamplesTest extends HttpTestSupport {
    String raw(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/contracts/" + name)) { return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8); }
    }
    JsonNode example(String name) throws Exception { return JSON.readTree(raw(name)); }
    /** Same property names (order is not part of the contract) and same JSON types, recursively. */
    void assertSameShape(JsonNode actual, JsonNode documented, String path) {
        assertThat(actual.getNodeType()).as(path).isEqualTo(documented.getNodeType());
        if (documented.isObject()) {
            assertThat(names(actual)).as(path + " properties").containsExactlyInAnyOrderElementsOf(names(documented));
            documented.fieldNames().forEachRemaining(n -> assertSameShape(actual.get(n), documented.get(n), path + "." + n));
        } else if (documented.isArray() && documented.size() > 0) {
            assertThat(actual.size()).as(path + " not empty").isGreaterThan(0);
            assertSameShape(actual.get(0), documented.get(0), path + "[0]");
        }
    }
    List<String> names(JsonNode node) { var out = new ArrayList<String>(); node.fieldNames().forEachRemaining(out::add); return out; }

    @Test void searchRequestAndResponseExamplesMatchTheServer() throws Exception {
        String doc = unique(); var created = create("Ana María Pérez", doc);
        var response = request("POST", "/api/v1/customers/search", seller(), raw("search-request.json").replace("{{document}}", doc), null);
        assertThat(response.statusCode()).isEqualTo(200);
        var documented = example("search-response.json");
        assertSameShape(json(response), documented, "$");
        assertThat(json(response).at("/data/0/customerId").asText()).isEqualTo(id(created));
        assertThat(json(response).at("/data/0/match").asText()).isEqualTo(documented.at("/data/0/match").asText());
        assertThat(json(response).get("meta")).isEqualTo(documented.get("meta"));
        assertThat(response.body()).doesNotContain("email", "phone", "address");   // a selector never needs contacts
    }

    @Test void validationErrorExampleMatchesTheServer() throws Exception {
        var response = request("POST", "/api/v1/customers/search", seller(), raw("search-invalid-request.json"), null);
        assertThat(response.statusCode()).isEqualTo(400);
        var documented = example("search-invalid-response.json");
        assertSameShape(json(response), documented, "$");
        assertThat(json(response).get("error")).isEqualTo(documented.get("error"));
        assertThat(json(response).get("message")).isEqualTo(documented.get("message"));
        assertThat(json(response).get("details")).isEqualTo(documented.get("details"));
    }

    @Test void activationResponseExampleMatchesTheCustomerResource() throws Exception {
        String id = id(create(unique())); deactivate(id);
        var response = request("POST", "/api/v1/customers/" + id + "/activate", admin(), null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertSameShape(json(response), example("activate-response.json"), "$");
        assertThat(json(response).get("active").asBoolean()).isTrue();
    }
}
