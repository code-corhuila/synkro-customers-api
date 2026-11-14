package co.edu.corhuila.synkro.customers.app;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** HU-CLI-05 / ADR-022: real HTTP and PostgreSQL preserve identities under folded lookup. */
class CustomerAmbiguityHttpTest extends HttpTestSupport {
    @Test void foldedExactMatchesPreserveBothStoredDocumentsAndRejectOnlyTheRawDuplicate() throws Exception {
        String first = id(create("Synthetic Alpha", "ABC123"));
        String second = id(create("Synthetic Beta", "abc123"));
        String prefix = id(create("A prefix", "ABC1239"));
        String name = id(create("ABC123 by name", unique()));
        assertThat(first).isNotEqualTo(second);
        for (String query : List.of("ABC123", "abc123")) {
            var response = search(seller(), query);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(ids(response)).containsExactly(first, second, prefix, name);
            assertThat(json(response).get("data").findValuesAsText("match"))
                    .containsExactly("DOCUMENT_EXACT", "DOCUMENT_EXACT", "DOCUMENT_PREFIX", "NAME");
            assertThat(json(response).get("data").findValuesAsText("identityDocument"))
                    .containsExactly("ABC123", "abc123", "ABC1239", json(response).at("/data/3/identityDocument").asText());
            assertThat(json(response).at("/meta/limit").asInt()).isEqualTo(5);
            assertThat(json(response).at("/meta/hasMore").asBoolean()).isFalse();
        }
        var duplicate = create("Duplicate", "ABC123");
        assertThat(duplicate.statusCode()).isEqualTo(422);
        assertThat(json(duplicate).get("message").asText()).isEqualTo("Identity document already belongs to a customer");
        assertThat(json(duplicate).get("error").asText()).isEqualTo("BUSINESS_RULE_VIOLATION");
    }

    @Test void sixExactMatchesExposeAmbiguityWithinFiveSlotsBeforeWeakerMatches() throws Exception {
        String suffix = digits();
        var expected = new ArrayList<String>();
        List<String> variants = List.of("ABC", "ABc", "AbC", "Abc", "aBC", "abc");
        for (int i = 0; i < variants.size(); i++)
            expected.add(id(create("Candidate " + i, variants.get(i) + suffix)));
        create("AAA weaker prefix", "abc" + suffix + "9");
        create("AAA name abc" + suffix, unique());
        var response = search(admin(), "abc" + suffix);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(ids(response)).containsExactlyElementsOf(expected.subList(0, 5));
        assertThat(json(response).get("data").findValuesAsText("match"))
                .containsExactly("DOCUMENT_EXACT", "DOCUMENT_EXACT", "DOCUMENT_EXACT", "DOCUMENT_EXACT", "DOCUMENT_EXACT");
        assertThat(json(response).at("/meta/hasMore").asBoolean()).isTrue();
        assertThat(json(response).at("/meta/limit").asInt()).isEqualTo(5);
    }

    @Test void accentVariantsTieByUuidAndStatusFilteringDoesNotMergeCustomers() throws Exception {
        String suffix = digits();
        String first = id(create("Same Name", "Núñez" + suffix));
        String second = id(create("Same Name", "nunez" + suffix));
        var expected = new ArrayList<>(List.of(first, second));
        expected.sort(String::compareTo);
        assertThat(ids(search(seller(), "NUNEZ" + suffix))).containsExactlyElementsOf(expected);
        deactivate(second);
        assertThat(ids(search(seller(), "nunez" + suffix))).containsExactly(first);
        assertThat(ids(search(admin(), java.util.Map.of("query", "nunez" + suffix, "status", "INACTIVE"))))
                .containsExactly(second);
    }
}
