package co.edu.corhuila.synkro.customers.domain.model;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class CustomerTest {
    @Test void businessAndStorageFailuresKeepSafeDistinctCatalogs() {
        var conflict=new CustomerFailure("BUSINESS_RULE_VIOLATION","identityDocument","Identity document already belongs to a customer");
        assertThat(conflict.code()).isEqualTo("BUSINESS_RULE_VIOLATION");
        assertThat(conflict.field()).isEqualTo("identityDocument");
        var storage=CustomerFailure.storage();
        assertThat(storage.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(storage.field()).isNull();
        assertThat(storage.getMessage()).doesNotContain("SELECT","password","jdbc:");
    }
    @Test void replacementAndDeactivationPreserveIdentity() {
        var id = UUID.randomUUID();
        var date = Instant.parse("2026-01-01T00:00:00Z");
        var original = new Customer(id, new CustomerFields("Ana", "doc", null, null, null), date, true);
        var updated = original.replace(new CustomerFields("Eva", "new", "eva@example.test", "", ""));
        assertThat(updated.customerId()).isEqualTo(id);
        assertThat(updated.registrationDate()).isEqualTo(date);
        assertThat(updated.fields().email()).isEqualTo("eva@example.test");
        assertThat(updated.deactivate().active()).isFalse();
        assertThat(updated.deactivate().deactivate()).isEqualTo(updated.deactivate());
    }
    @Test void fieldsValidateLimitsWithoutInventingDocumentFormats() {
        assertThatCode(() -> new CustomerFields("Ana", "any / document", null, "", "")).doesNotThrowAnyException();
        for (String name : new String[]{"", " ", "x".repeat(151)})
            assertThatThrownBy(() -> new CustomerFields(name, "doc", null, null, null)).isInstanceOf(CustomerFailure.class);
        assertThatThrownBy(() -> new CustomerFields("Ana", "x".repeat(31), null, null, null)).isInstanceOf(CustomerFailure.class);
        assertThatThrownBy(() -> new CustomerFields("Ana", "doc", "x".repeat(256), null, null)).isInstanceOf(CustomerFailure.class);
        assertThatThrownBy(() -> new CustomerFields("Ana", "doc", null, "x".repeat(21), null)).isInstanceOf(CustomerFailure.class);
        assertThatThrownBy(() -> new CustomerFields("Ana", "doc", null, null, "x".repeat(256))).isInstanceOf(CustomerFailure.class);
        assertThatThrownBy(() -> new CustomerFields("Ana", null, null, null, null)).isInstanceOf(CustomerFailure.class);
    }
}
