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
    /** At most {@link SearchResult#LIMIT} suggestions of the requested status, strongest match first. */
    SearchResult search(Search search);
    /**
     * Atomic inactive-to-active transition that records the operator in the same transaction. An already active
     * customer is returned unchanged without a new audit record; an unknown id fails with NOT_FOUND.
     */
    Customer activate(UUID id, UUID operator, String correlationId);
    boolean healthy();
}
