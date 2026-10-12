package co.edu.corhuila.synkro.customers.domain.model;

public final class CustomerFailure extends RuntimeException {
    private final String code;
    private final String field;
    public CustomerFailure(String code, String field, String message) {
        super(message); this.code = code; this.field = field;
    }
    public String code() { return code; }
    public String field() { return field; }
    public static CustomerFailure invalid(String field) {
        return new CustomerFailure("VALIDATION_ERROR", field, "Invalid field value");
    }
    public static CustomerFailure missing() {
        return new CustomerFailure("NOT_FOUND", null, "Customer not found");
    }
    public static CustomerFailure storage() {
        return new CustomerFailure("INTERNAL_ERROR", null, "Storage operation failed");
    }
}
