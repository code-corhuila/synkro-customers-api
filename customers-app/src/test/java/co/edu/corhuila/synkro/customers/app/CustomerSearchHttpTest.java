package co.edu.corhuila.synkro.customers.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** HU-CLI-05 Scenario 4: bounded, case/accent-insensitive search through the real HTTP stack and PostgreSQL. */
@ExtendWith(OutputCaptureExtension.class)
class CustomerSearchHttpTest extends HttpTestSupport {
    /** Lower-case letters unique to one test, so the shared database never leaks rows between tests. */
    String tag() { var random = new Random(); var b = new StringBuilder("q"); for (int i = 0; i < 8; i++) b.append((char) ('a' + random.nextInt(26))); return b.toString(); }

    @Test void findsByNameIgnoringCaseAccentsAndWordOrder() throws Exception {
        String tag = tag(); String id = id(create("Ana María Pérez Muñoz " + tag, unique()));
        for (String query : List.of(tag, tag.toUpperCase(), "ana " + tag, "ANA MARIA " + tag, "perez ana " + tag, "MUÑOZ " + tag, "munoz " + tag, "marí " + tag, "ÁNA MARÍA PÉREZ MUNOZ " + tag, "  " + tag + "  ")) {
            var r = search(seller(), query);
            assertThat(r.statusCode()).as(query).isEqualTo(200);
            assertThat(ids(r)).as(query).containsExactly(id);
            assertThat(json(r).at("/data/0/match").asText()).isEqualTo("NAME");
        }
        assertThat(ids(search(seller(), "ana zzzz" + tag))).isEmpty();
        var none = search(seller(), "zz" + tag + "none");
        assertThat(none.statusCode()).isEqualTo(200);
        assertThat(json(none).get("data").size()).isZero();
        assertThat(json(none).at("/meta/hasMore").asBoolean()).isFalse();
    }

    @Test void findsByDocumentAndOrdersExactThenPrefixThenName() throws Exception {
        String tag = tag(), base = digits();
        String exact = id(create("Documento exacto " + tag, base));
        String prefix = id(create("Documento prefijo " + tag, base + "99"));
        String byName = id(create("Cliente " + base + " " + tag, unique()));
        var r = search(admin(), base);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(ids(r)).containsExactly(exact, prefix, byName);
        assertThat(json(r).get("data").findValuesAsText("match")).containsExactly("DOCUMENT_EXACT", "DOCUMENT_PREFIX", "NAME");
        assertThat(ids(search(admin(), base.substring(0, 8)))).startsWith(exact);   // prefix of the same document
        assertThat(ids(search(admin(), base + "9"))).containsExactly(prefix);
        assertThat(ids(search(admin(), base.substring(2)))).containsExactly(byName); // only a name contains it
    }

    @Test void exactDocumentIsAlwaysFirstAndSelectableWithEnter() throws Exception {
        String tag = tag(), doc = "AB-" + digits().substring(0, 6);
        String other = id(create("Aaa primero " + tag + " " + doc.toLowerCase(), unique()));   // alphabetically before, matches by name
        String exact = id(create("Zzz último " + tag, doc));
        var r = search(seller(), doc.toLowerCase());                       // case-insensitive document
        assertThat(ids(r)).containsExactly(exact, other);
        assertThat(json(r).at("/data/0/match").asText()).isEqualTo("DOCUMENT_EXACT");
    }

    @Test void returnsAtMostFiveInAStableOrderAndSaysWhenThereAreMore() throws Exception {
        String tag = tag(); var created = new ArrayList<String>();
        for (int i = 0; i < 7; i++) created.add(id(create("Cliente " + (char) ('a' + i) + " " + tag, unique())));
        var first = search(seller(), tag);
        assertThat(json(first).get("data").size()).isEqualTo(5);
        assertThat(json(first).at("/meta/hasMore").asBoolean()).isTrue();
        assertThat(json(first).at("/meta/limit").asInt()).isEqualTo(5);
        assertThat(ids(first)).containsExactlyElementsOf(created.subList(0, 5));         // folded name ascending
        for (int i = 0; i < 3; i++) assertThat(search(seller(), tag).body()).isEqualTo(first.body());   // repeatable
        deactivate(created.get(5)); deactivate(created.get(6));
        var five = search(seller(), tag);
        assertThat(json(five).get("data").size()).isEqualTo(5);
        assertThat(json(five).at("/meta/hasMore").asBoolean()).isFalse();
        deactivate(created.get(4));
        assertThat(json(search(seller(), tag)).get("data").size()).isEqualTo(4);
    }

    @Test void namesWithTheSameFoldedValueAreOrderedByCustomerId() throws Exception {
        String tag = tag(); var ids = new ArrayList<String>();
        for (String name : List.of("Álvaro ", "alvaro ", "ALVARO ")) ids.add(id(create(name + tag, unique())));
        var got = ids(search(seller(), tag));
        // equal folded names ("alvaro tag") only tie-break by id
        assertThat(got).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(got).isSortedAccordingTo(Comparator.naturalOrder());
    }

    @Test void inactiveCustomersAreNeverSuggestedByDefaultAndOnlyAdminCanLookForThem() throws Exception {
        String tag = tag(); String active = id(create("Activo " + tag, unique())), inactive = id(create("Inactivo " + tag, unique()));
        deactivate(inactive);
        for (String who : List.of(admin(), seller())) {
            assertThat(ids(search(who, tag))).containsExactly(active);
            assertThat(ids(search(who, Map.of("query", tag, "status", "ACTIVE")))).containsExactly(active);
        }
        var inactives = search(admin(), Map.of("query", tag, "status", "INACTIVE"));
        assertThat(ids(inactives)).containsExactly(inactive);
        assertThat(json(inactives).at("/data/0/active").asBoolean()).isFalse();
        var denied = search(seller(), Map.of("query", tag, "status", "INACTIVE"));
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(json(denied).get("error").asText()).isEqualTo("FORBIDDEN");
        assertThat(denied.body()).doesNotContain(inactive, tag);
    }

    @Test void historicalCustomersWithoutContactsAreSearchableToo() throws Exception {
        String tag = tag(), doc = unique(); String legacy = legacyCustomer("Histórico " + tag, doc, true);
        assertThat(ids(search(seller(), tag))).containsExactly(legacy);
        assertThat(ids(search(seller(), doc))).containsExactly(legacy);
    }

    @Test void wildcardsAreLiteralText() throws Exception {
        String tag = tag();
        String percent = id(create("Descuento 100% " + tag, unique())), plain = id(create("Descuento 100 abc " + tag, unique()));
        String underscore = id(create("Cod_x " + tag, unique())), other = id(create("Codzx " + tag, unique()));
        String slash = id(create("Ruta a\\b " + tag, unique()));
        assertThat(ids(search(seller(), "100% " + tag))).containsExactly(percent);
        assertThat(ids(search(seller(), "%" + tag))).isEmpty();
        assertThat(ids(search(seller(), "cod_x " + tag))).containsExactly(underscore);
        assertThat(ids(search(seller(), "a\\b " + tag))).containsExactly(slash);
        assertThat(plain).isNotEqualTo(other);
    }

    @Test void responseCarriesOnlyWhatASelectorNeeds() throws Exception {
        String tag = tag(); create("Mínimo " + tag, unique());
        var r = search(seller(), tag);
        var item = json(r).at("/data/0");
        assertThat(item.fieldNames()).toIterable().containsExactlyInAnyOrder("customerId", "name", "identityDocument", "active", "match");
        assertThat(json(r).fieldNames()).toIterable().containsExactlyInAnyOrder("data", "meta");
        assertThat(json(r).get("meta").fieldNames()).toIterable().containsExactlyInAnyOrder("limit", "hasMore");
        assertThat(r.body()).doesNotContain("cliente@example.test", "3001234567", "Calle 1");
        assertThat(r.headers().firstValue("X-Correlation-Id")).hasValue("test-correlation");
    }

    @Test void invalidBodiesAreRejectedWithoutLookingAnything() throws Exception {
        for (String body : List.of("{}", "{\"query\":\"\"}", "{\"query\":\" \"}", "{\"query\":\"a\"}", "{\"query\":\" a \"}", "{\"query\":null}", "{\"query\":12}",
                "{\"query\":[\"ab\"]}", "{\"query\":\"" + "x".repeat(101) + "\"}", "{\"query\":\"ab\",\"status\":\"all\"}", "{\"query\":\"ab\",\"status\":\"\"}",
                "{\"query\":\"ab\",\"status\":null}", "{\"query\":\"ab\",\"status\":true}", "{\"query\":\"ab\",\"page\":1}", "{\"query\":\"ab\",\"limit\":50}",
                "{\"query\":\"ab\",\"Query\":\"cd\"}", "[]", "\"ab\"", "{", "not json")) {
            var r = request("POST", "/api/v1/customers/search", admin(), body, null);
            assertThat(r.statusCode()).as(body).isEqualTo(400);
            assertThat(json(r).get("error").asText()).isEqualTo("VALIDATION_ERROR");
            assertThat(json(r).get("traceId").asText()).isEqualTo("test-correlation");
        }
        assertThat(request("POST", "/api/v1/customers/search", admin(), null, null).statusCode()).isIn(400, 415);   // no body at all
        assertThat(json(search(admin(), "x")).get("details").findValuesAsText("field")).containsExactly("query");
        assertThat(json(search(admin(), Map.of("query", "ab", "status", "ALL"))).get("details").findValuesAsText("field")).containsExactly("status");
        assertThat(json(search(admin(), Map.of("query", "ab", "extra", 1))).get("details").findValuesAsText("field")).containsExactly("extra");
        assertThat(search(admin(), "ab").statusCode()).isEqualTo(200);                   // boundaries are inclusive
        assertThat(search(admin(), "x".repeat(100)).statusCode()).isEqualTo(200);
        assertThat(search(admin(), "  " + "y".repeat(100) + "  ").statusCode()).isEqualTo(200);   // measured after trimming
    }

    @Test void onlyHumanAdminAndSalespersonMaySearch() throws Exception {
        String tag = tag(); create("Permisos " + tag, unique());
        assertThat(request("POST", "/api/v1/customers/search", null, "{\"query\":\"ab\"}", null).statusCode()).isEqualTo(401);
        assertThat(request("POST", "/api/v1/customers/search", "not-a-token", "{\"query\":\"ab\"}", null).statusCode()).isEqualTo(401);
        for (String role : List.of(admin(), seller())) assertThat(search(role, tag).statusCode()).isEqualTo(200);
        for (String denied : List.of(inventory(), workflow(), token("synkro-worker", "SERVICE", List.of("customers:read")), token(UUID.randomUUID().toString(), "SALESPERSON_ADMIN", List.of()))) {
            var r = search(denied, tag);
            assertThat(r.statusCode()).isEqualTo(403);
            assertThat(r.body()).doesNotContain(tag, "Permisos");
        }
        for (String method : List.of("GET", "PUT", "DELETE", "PATCH"))
            assertThat(request(method, "/api/v1/customers/search", admin(), "{\"query\":\"ab\"}", null).statusCode()).as(method).isIn(400, 404, 405);
    }

    @Test void searchTextAndCustomerDataNeverReachTheLogs(CapturedOutput output) throws Exception {
        String tag = tag(), doc = digits(); create("Privado Ñandú " + tag, doc);
        assertThat(search(seller(), "privado nandu " + tag).statusCode()).isEqualTo(200);
        assertThat(search(seller(), doc).statusCode()).isEqualTo(200);
        assertThat(search(seller(), "zz" + tag + "nomatch").statusCode()).isEqualTo(200);
        assertThat(search(admin(), Map.of("query", tag, "status", "NOPE")).statusCode()).isEqualTo(400);
        assertThat(request("POST", "/api/v1/customers/search", admin(), "{\"query\":\"" + tag + "\"", null).statusCode()).isEqualTo(400);
        assertThat(output.getAll()).doesNotContain(tag, doc, "Privado", "Ñandú", "nandu");
    }
}
