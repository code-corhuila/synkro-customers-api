package co.edu.corhuila.synkro.customers.application.port.out;

import co.edu.corhuila.synkro.customers.application.port.in.CustomerUseCases.*;
import co.edu.corhuila.synkro.customers.domain.model.*;
import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository {
    /** Owns one transaction for customer + key; existing key returns its resource. */
    Created createOnce(String key, CustomerFields fields);
    Optional<Customer> find(UUID id);
    Page list(Query query);
    /** Atomic replacement only while active; preserves identity/date. */
    Customer replace(UUID id, CustomerFields fields);
    Customer deactivate(UUID id);
    boolean healthy();
}
