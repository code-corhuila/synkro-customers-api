package co.edu.corhuila.synkro.customers.application.usecase;

import co.edu.corhuila.synkro.customers.application.port.in.*;
import co.edu.corhuila.synkro.customers.application.port.out.CustomerRepository;
import co.edu.corhuila.synkro.customers.domain.model.*;
import java.util.UUID;

public final class CustomerService implements CustomerUseCases {
    private final CustomerRepository repository;
    public CustomerService(CustomerRepository repository) { this.repository = repository; }
    public Created create(Caller caller, String key, CustomerFields fields) {
        caller.authorize(false);
        CustomerFields.length(key, "Idempotency-Key", 128, true);
        if (key.codePointCount(0, key.length()) < 8) throw CustomerFailure.invalid("Idempotency-Key");
        return repository.createOnce(key, fields);
    }
    public Customer get(Caller caller, UUID id) {
        caller.authorize(true);
        return repository.find(id).filter(Customer::active).orElseThrow(CustomerFailure::missing);
    }
    public Page list(Caller caller, Query query) { caller.authorize(false); return repository.list(query); }
    public Customer update(Caller caller, UUID id, CustomerFields fields) { caller.authorize(false); return repository.replace(id, fields); }
    public Customer deactivate(Caller caller, UUID id) { caller.authorize(false); return repository.deactivate(id); }
}
