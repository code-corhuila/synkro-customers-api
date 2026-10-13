package co.edu.corhuila.synkro.customers.application.port.in;

import co.edu.corhuila.synkro.customers.domain.model.*;
import java.util.List;
import java.util.UUID;

public interface CustomerUseCases {
    Created create(Caller caller, String key, CustomerFields fields);
    Customer get(Caller caller, UUID id);
    Page list(Caller caller, Query query);
    Customer update(Caller caller, UUID id, CustomerFields fields);
    Customer deactivate(Caller caller, UUID id);
    record Created(Customer customer, boolean created) {}
    record Page(List<Customer> data, long total, int page, int limit) {
        public Page { data = List.copyOf(data); }
        public long totalPages() { return (total + limit - 1) / limit; }
    }
    record Query(String identityDocument, Boolean active, int page, int limit) {
        public Query {
            if (identityDocument != null) CustomerFields.length(identityDocument, "identityDocument", 30, true);
            if (page < 1) throw CustomerFailure.invalid("page");
            if (limit < 1 || limit > 100) throw CustomerFailure.invalid("limit");
        }
        public long offset() { return ((long) page - 1) * limit; }
    }
}
