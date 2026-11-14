package co.edu.corhuila.synkro.customers.application.usecase;

import co.edu.corhuila.synkro.customers.application.port.in.*;
import co.edu.corhuila.synkro.customers.application.port.out.CustomerRepository;
import co.edu.corhuila.synkro.customers.domain.model.*;
import java.util.UUID;

public final class CustomerService implements CustomerUseCases {
    private static final int CORRELATION_MAX = 128;
    private final CustomerRepository repository;
    public CustomerService(CustomerRepository repository) { this.repository = repository; }
    public Created create(Caller caller, String key, CustomerFields fields) {
        caller.authorize(false);
        CustomerFields.length(key, "Idempotency-Key", 128, true);
        if (key.codePointCount(0, key.length()) < 8) throw CustomerFailure.invalid("Idempotency-Key");
        return repository.createOnce(key, fields.requireCompleteForWrite());
    }
    public Customer get(Caller caller, UUID id) {
        caller.authorize(true);
        return repository.find(id).filter(Customer::active).orElseThrow(CustomerFailure::missing);
    }
    public Page list(Caller caller, Query query) { caller.authorize(false); return repository.list(query); }
    public Customer update(Caller caller, UUID id, CustomerFields fields) {
        caller.authorize(false);
        return repository.replace(id, fields.requireCompleteForWrite());
    }
    public Customer deactivate(Caller caller, UUID id) { caller.authorize(false); return repository.deactivate(id); }
    public SearchResult search(Caller caller, Search search) {
        caller.authorize(false);
        // Inactive customers are never suggested to a seller; only ADMIN may look for them (to reactivate).
        if (search.status() == Search.Status.INACTIVE) caller.authorizeAdmin();
        return repository.search(search);
    }
    public Customer activate(Caller caller, UUID id, String correlationId) {
        caller.authorizeAdmin();
        UUID operator;
        try { operator = UUID.fromString(caller.subject()); }
        catch (IllegalArgumentException e) { throw CustomerFailure.forbidden(); }
        return repository.activate(id, operator, bounded(correlationId));
    }
    private static String bounded(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) return null;
        int length = correlationId.codePointCount(0, correlationId.length());
        return length <= CORRELATION_MAX ? correlationId : correlationId.substring(0, correlationId.offsetByCodePoints(0, CORRELATION_MAX));
    }
}
