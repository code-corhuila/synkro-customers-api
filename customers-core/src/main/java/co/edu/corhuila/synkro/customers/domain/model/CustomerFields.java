package co.edu.corhuila.synkro.customers.domain.model;

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
}
