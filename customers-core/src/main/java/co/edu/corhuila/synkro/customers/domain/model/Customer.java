package co.edu.corhuila.synkro.customers.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Customer(UUID customerId, CustomerFields fields, Instant registrationDate, boolean active) {
    public Customer {
        Objects.requireNonNull(customerId); Objects.requireNonNull(fields); Objects.requireNonNull(registrationDate);
    }
    public Customer replace(CustomerFields replacement) {
        if (!active) throw CustomerFailure.missing();
        return new Customer(customerId, replacement, registrationDate, true);
    }
    public Customer deactivate() { return active ? new Customer(customerId, fields, registrationDate, false) : this; }
}
