package co.edu.corhuila.synkro.customers.domain.model;

import java.util.List;

public final class CustomerFailure extends RuntimeException {
    /** One rejected field; {@code message} is a stable code: required, invalid_format, too_long or invalid_length. */
    public record Issue(String field, String message) {}
    private final String code;
    private final String field;
    private final List<Issue> issues;
    public CustomerFailure(String code, String field, String message) {
        super(message); this.code = code; this.field = field;
        this.issues = field == null ? List.of() : List.of(new Issue(field, message));
    }
    private CustomerFailure(List<Issue> issues) {
        super("Invalid request fields"); this.code = "VALIDATION_ERROR"; this.field = issues.get(0).field(); this.issues = List.copyOf(issues);
    }
    public String code() { return code; }
    /** First rejected field, kept for callers that report a single field. */
    public String field() { return field; }
    /** Every rejected field, in the order name, identityDocument, email, phone, address. */
    public List<Issue> issues() { return issues; }
    public static CustomerFailure invalid(String field) {
        return new CustomerFailure("VALIDATION_ERROR", field, "Invalid field value");
    }
    public static CustomerFailure invalid(List<Issue> issues) { return new CustomerFailure(issues); }
    public static CustomerFailure missing() {
        return new CustomerFailure("NOT_FOUND", null, "Customer not found");
    }
    public static CustomerFailure forbidden() {
        return new CustomerFailure("FORBIDDEN", null, "Operation is not authorized");
    }
    public static CustomerFailure storage() {
        return new CustomerFailure("INTERNAL_ERROR", null, "Storage operation failed");
    }
}
