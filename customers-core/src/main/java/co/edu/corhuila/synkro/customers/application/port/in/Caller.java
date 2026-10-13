package co.edu.corhuila.synkro.customers.application.port.in;

import co.edu.corhuila.synkro.customers.domain.model.CustomerFailure;
import java.util.Set;

/** Trusted identity supplied only after local signature and claims validation. */
public record Caller(String subject, Set<String> roles, Set<String> permissions) {
    public Caller { roles = Set.copyOf(roles); permissions = Set.copyOf(permissions); }
    public void authorize(boolean readById) {
        boolean human = !roles.contains("SERVICE") && (roles.equals(Set.of("ADMIN")) || roles.equals(Set.of("SALESPERSON")));
        boolean workflow = readById && subject.equals("synkro-workflow") && roles.equals(Set.of("SERVICE")) && permissions.contains("customers:read");
        if (!human && !workflow) throw new CustomerFailure("FORBIDDEN", null, "Operation is not authorized");
    }
}
