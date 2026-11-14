package co.edu.corhuila.synkro.customers.domain.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Editable customer data. The canonical constructor only enforces storage limits so that historical rows (stored
 * before HU-CLI-05 with missing contacts) can still be read; every write goes through {@link #forWrite} or
 * {@link #requireCompleteForWrite()}, which also require complete, valid contacts and report all problems together.
 */
public record CustomerFields(String name, String identityDocument, String email, String phone, String address) {
    public CustomerFields {
        length(name, "name", 150, true);
        length(identityDocument, "identityDocument", 30, true);
        length(email, "email", 255, false);
        length(phone, "phone", 20, false);
        length(address, "address", 255, false);
    }
    public static void length(String value, String field, int max, boolean required) {
        if ((required && (value == null || value.isBlank())) ||
                (value != null && value.codePointCount(0, value.length()) > max)) throw CustomerFailure.invalid(field);
    }
    /** Builds the data of a create/replace request; throws one VALIDATION_ERROR listing every invalid field. */
    public static CustomerFields forWrite(String name, String identityDocument, String email, String phone, String address) {
        var issues = issues(name, identityDocument, email, phone, address);
        if (!issues.isEmpty()) throw CustomerFailure.invalid(issues);
        return new CustomerFields(name, identityDocument, email, phone, address);
    }
    public CustomerFields requireCompleteForWrite() {
        var issues = issues(name, identityDocument, email, phone, address);
        if (!issues.isEmpty()) throw CustomerFailure.invalid(issues);
        return this;
    }
    private static List<CustomerFailure.Issue> issues(String name, String document, String email, String phone, String address) {
        var issues = new ArrayList<CustomerFailure.Issue>();
        text(issues, "name", name, 150);
        text(issues, "identityDocument", document, 30);
        add(issues, "email", ContactRules.email(email));
        add(issues, "phone", ContactRules.phone(phone));
        add(issues, "address", ContactRules.address(address));
        return issues;
    }
    private static void text(List<CustomerFailure.Issue> issues, String field, String value, int max) {
        if (value == null || value.isBlank()) issues.add(new CustomerFailure.Issue(field, "required"));
        else if (value.codePointCount(0, value.length()) > max) issues.add(new CustomerFailure.Issue(field, "too_long"));
    }
    private static void add(List<CustomerFailure.Issue> issues, String field, String code) {
        if (code != null) issues.add(new CustomerFailure.Issue(field, code));
    }
}
