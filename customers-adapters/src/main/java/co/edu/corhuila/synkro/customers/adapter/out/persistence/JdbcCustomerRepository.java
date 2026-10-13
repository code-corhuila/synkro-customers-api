package co.edu.corhuila.synkro.customers.adapter.out.persistence;

import co.edu.corhuila.synkro.customers.application.port.in.CustomerUseCases.*;
import co.edu.corhuila.synkro.customers.application.port.out.CustomerRepository;
import co.edu.corhuila.synkro.customers.domain.model.*;
import org.postgresql.util.PSQLException;
import org.springframework.dao.*;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.function.Supplier;

public final class JdbcCustomerRepository implements CustomerRepository {
    private static final String COLUMNS = "customer_id,name,identity_document,email,phone,address,registration_date,active";
    private static final String TABLE = "customers_schema.customer";
    private static final RowMapper<Customer> ROW = (r,n) -> new Customer(r.getObject("customer_id",UUID.class),
            new CustomerFields(r.getString("name"),r.getString("identity_document"),r.getString("email"),r.getString("phone"),r.getString("address")),
            r.getTimestamp("registration_date").toInstant(),r.getBoolean("active"));
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public JdbcCustomerRepository(JdbcTemplate jdbc, TransactionTemplate transactions) { this.jdbc=jdbc; this.transactions=transactions; }
    private Optional<Customer> one(String sql,Object... args) { return jdbc.query(sql,ROW,args).stream().findFirst(); }
    private Optional<Customer> byKey(String key) {
        return one("SELECT c.* FROM "+TABLE+" c JOIN customers_schema.idempotency_key k USING(customer_id) WHERE k.key=?",key);
    }
    public Created createOnce(String key,CustomerFields fields) {
        try {
            return transactions.execute(status -> {
                // Per-key cross-process serialization; collisions only reduce concurrency.
                jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",r -> { return null; },key);
                var existing=byKey(key);
                if(existing.isPresent()) return new Created(existing.get(),false);
                var customer=one("INSERT INTO "+TABLE+" (name,identity_document,email,phone,address) VALUES (?,?,?,?,?) RETURNING "+COLUMNS,
                        fields.name(),fields.identityDocument(),fields.email(),fields.phone(),fields.address()).orElseThrow(CustomerFailure::storage);
                jdbc.update("INSERT INTO customers_schema.idempotency_key(key,customer_id) VALUES (?,?)",key,customer.customerId());
                return new Created(customer,true);
            });
        } catch (DuplicateKeyException e) {
            // TransactionTemplate has rolled back. Never read the aborted transaction.
            return safe(() -> {
                var existing=byKey(key);
                if(existing.isPresent()) return new Created(existing.get(),false);
                throw translate(e);
            });
        } catch (DataAccessException e) { throw CustomerFailure.storage(); }
    }
    public Optional<Customer> find(UUID id) { return safe(() -> one("SELECT "+COLUMNS+" FROM "+TABLE+" WHERE customer_id=?",id)); }
    public Page list(Query q) {
        return safe(() -> transactions.execute(status -> {
            String where=" WHERE (?::text IS NULL OR identity_document=?) AND (?::boolean IS NULL OR active=?)";
            Object[] filters={q.identityDocument(),q.identityDocument(),q.active(),q.active()};
            long total=Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM "+TABLE+where,Long.class,filters));
            var rows=jdbc.query("SELECT "+COLUMNS+" FROM "+TABLE+where+" ORDER BY registration_date DESC,customer_id DESC LIMIT ? OFFSET ?",ROW,
                    q.identityDocument(),q.identityDocument(),q.active(),q.active(),q.limit(),q.offset());
            return new Page(rows,total,q.page(),q.limit());
        }));
    }
    public Customer replace(UUID id,CustomerFields f) {
        return safe(() -> one("UPDATE "+TABLE+" SET name=?,identity_document=?,email=?,phone=?,address=? WHERE customer_id=? AND active=true RETURNING "+COLUMNS,
                f.name(),f.identityDocument(),f.email(),f.phone(),f.address(),id).orElseThrow(CustomerFailure::missing));
    }
    public Customer deactivate(UUID id) {
        return safe(() -> transactions.execute(status -> one("UPDATE "+TABLE+" SET active=false WHERE customer_id=? AND active=true RETURNING "+COLUMNS,id)
                .or(() -> one("SELECT "+COLUMNS+" FROM "+TABLE+" WHERE customer_id=?",id)).orElseThrow(CustomerFailure::missing)));
    }
    public boolean healthy() {
        try { jdbc.queryForObject("SELECT count(*) FROM "+TABLE+" WHERE false",Long.class); return true; }
        catch (DataAccessException e) { return false; }
    }
    private <T> T safe(Supplier<T> operation) {
        try { return operation.get(); }
        catch (DuplicateKeyException e) { throw translate(e); }
        catch (DataAccessException e) { throw CustomerFailure.storage(); }
    }
    private CustomerFailure translate(DuplicateKeyException e) {
        for(Throwable cause=e;cause!=null;cause=cause.getCause())
            if(cause instanceof PSQLException pg && pg.getServerErrorMessage()!=null && "uq_customer_identity_document".equals(pg.getServerErrorMessage().getConstraint()))
                return new CustomerFailure("BUSINESS_RULE_VIOLATION","identityDocument","Identity document already belongs to a customer");
        return CustomerFailure.storage();
    }
}
