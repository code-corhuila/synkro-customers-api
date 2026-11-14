package co.edu.corhuila.synkro.customers.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** HU-CLI-05: required contacts, written before the production rules (synthetic data only). */
class CustomerFieldsTest {
    static final String EMAIL = "ana@example.test", PHONE = "3001234567", ADDRESS = "Calle 1 # 2-3";

    static CustomerFailure rejection(String name, String document, String email, String phone, String address) {
        return catchThrowableOfType(CustomerFailure.class, () -> CustomerFields.forWrite(name, document, email, phone, address));
    }
    static List<String> fieldsOf(CustomerFailure failure) { return failure.issues().stream().map(CustomerFailure.Issue::field).toList(); }

    @Test void validContactsAreStoredExactlyAsSent() {
        var fields = CustomerFields.forWrite("Ana", "doc", "Ana.Perez+tag@Example.TEST", "0123456789", "  Calle 1  ");
        assertThat(fields.email()).isEqualTo("Ana.Perez+tag@Example.TEST");   // no lowercasing
        assertThat(fields.phone()).isEqualTo("0123456789");                   // leading zero kept, text
        assertThat(fields.address()).isEqualTo("  Calle 1  ");                // no trimming
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "   ", "\t", "\n", " ", " 　", "​"})
    void missingEmptyAndBlankContactsAreRejectedField(String blank) {
        assertThat(fieldsOf(rejection("Ana", "doc", blank, PHONE, ADDRESS))).containsExactly("email");
        assertThat(fieldsOf(rejection("Ana", "doc", EMAIL, blank, ADDRESS))).containsExactly("phone");
        assertThat(fieldsOf(rejection("Ana", "doc", EMAIL, PHONE, blank))).containsExactly("address");
        assertThat(rejection("Ana", "doc", blank, PHONE, ADDRESS).issues().get(0).message()).isEqualTo("required");
    }

    @ParameterizedTest @ValueSource(strings = {
            "a@example.test", "a.b@example.test", "a+b@sub.example.test", "O'Brien@example.co", "a_b-c@ex-ample.test",
            "UPPER@EXAMPLE.TEST", "x@xn--p1ai.xn--p1ai", "1@2.co"})
    void acceptsConventionalAddresses(String email) {
        assertThatCode(() -> CustomerFields.forWrite("Ana", "doc", email, PHONE, ADDRESS)).doesNotThrowAnyException();
    }

    @ParameterizedTest @ValueSource(strings = {
            "plain", "a@b", "a@b.c", "a@.test", "a@test.", "@example.test", "a@@example.test", "a@b@example.test", "a b@example.test",
            " a@example.test", "a@example.test ", "a@exa mple.test", ".a@example.test", "a.@example.test", "a..b@example.test",
            "a@-example.test", "a@example-.test", "a@exam_ple.test", "a@example..test", "a@ex.123", "ñ@example.test", "a@exámple.test",
            "a@example.test\n", "<a@example.test>", "a,b@example.test", "a(b)@example.test"})
    void rejectsMalformedAddresses(String email) {
        var failure = rejection("Ana", "doc", email, PHONE, ADDRESS);
        assertThat(fieldsOf(failure)).containsExactly("email");
        assertThat(failure.issues().get(0).message()).isEqualTo("invalid_format");
    }

    @Test void emailLengthIsCountedInCodePointsAndBoundedByStorage() {
        String local64 = "a".repeat(64), label63 = "b".repeat(63);
        assertThatCode(() -> CustomerFields.forWrite("Ana", "doc", local64 + "@" + label63 + ".example.test", PHONE, ADDRESS)).doesNotThrowAnyException();
        assertThat(rejection("Ana", "doc", "a".repeat(65) + "@example.test", PHONE, ADDRESS).issues().get(0).message()).isEqualTo("invalid_format");
        assertThat(rejection("Ana", "doc", "a@" + "b".repeat(64) + ".test", PHONE, ADDRESS).issues().get(0).message()).isEqualTo("invalid_format");
        String max = "a@" + ("c".repeat(62) + ".").repeat(3) + "c".repeat(59) + ".test";   // exactly 255 code points
        assertThat(max.length()).isEqualTo(255);
        assertThatCode(() -> CustomerFields.forWrite("Ana", "doc", max, PHONE, ADDRESS)).doesNotThrowAnyException();
        assertThat(rejection("Ana", "doc", max + "x", PHONE, ADDRESS).issues().get(0).message()).isEqualTo("too_long");
    }

    @ParameterizedTest @ValueSource(strings = {"3001234567", "0000000000", "0123456789", "9999999999"})
    void phoneAcceptsExactlyTenAsciiDigits(String phone) {
        assertThat(CustomerFields.forWrite("Ana", "doc", EMAIL, phone, ADDRESS).phone()).isEqualTo(phone);
    }

    @ParameterizedTest @ValueSource(strings = {
            "123456789", "12345678901", "300123456a", "abcdefghij", "+573001234567", "573001234567", "300.1234567", "3001234567.0",
            "300 123 4567", "300-123-4567", "(300)1234567", " 3001234567", "3001234567 ", "3001234567\n", "-300123456", "3.0012E+9",
            "٣٠٠١٢٣٤٥٦٧",   // Arabic-Indic digits
            "３００１２３４５６７",   // full-width digits
            "3001234567́"})
    void phoneRejectsEverythingElseWithoutFixingIt(String phone) {
        var failure = rejection("Ana", "doc", EMAIL, phone, ADDRESS);
        assertThat(fieldsOf(failure)).containsExactly("phone");
        assertThat(failure.issues().get(0).message()).isEqualTo("invalid_format");
    }

    @Test void addressAcceptsAnyNonBlankTextUpToTheLimitInCodePoints() {
        assertThatCode(() -> CustomerFields.forWrite("Ana", "doc", EMAIL, PHONE, "x")).doesNotThrowAnyException();
        assertThatCode(() -> CustomerFields.forWrite("Ana", "doc", EMAIL, PHONE, "x".repeat(255))).doesNotThrowAnyException();
        assertThatCode(() -> CustomerFields.forWrite("Ana", "doc", EMAIL, PHONE, "🏠".repeat(255))).doesNotThrowAnyException(); // 255 code points, 510 chars
        var failure = rejection("Ana", "doc", EMAIL, PHONE, "x".repeat(256));
        assertThat(failure.issues()).extracting(CustomerFailure.Issue::message).containsExactly("too_long");
    }

    @Test void everyInvalidFieldIsReportedTogetherInFieldOrder() {
        var failure = rejection("", "x".repeat(31), null, "123", "   ");
        assertThat(failure.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(failure.issues()).extracting(CustomerFailure.Issue::field, CustomerFailure.Issue::message).containsExactly(
                tuple("name", "required"), tuple("identityDocument", "too_long"), tuple("email", "required"),
                tuple("phone", "invalid_format"), tuple("address", "required"));
        assertThat(failure.field()).isEqualTo("name");   // first issue stays available to existing callers
    }

    @Test void completenessIsRequiredForWritesButHistoricalRowsRemainRepresentable() {
        var historical = new CustomerFields("Ana", "doc", null, null, null);   // as stored before HU-CLI-05
        assertThat(historical.phone()).isNull();
        var failure = catchThrowableOfType(CustomerFailure.class, historical::requireCompleteForWrite);
        assertThat(fieldsOf(failure)).containsExactly("email", "phone", "address");
        assertThatCode(() -> CustomerFields.forWrite("Ana", "doc", EMAIL, PHONE, ADDRESS).requireCompleteForWrite()).doesNotThrowAnyException();
    }
}
