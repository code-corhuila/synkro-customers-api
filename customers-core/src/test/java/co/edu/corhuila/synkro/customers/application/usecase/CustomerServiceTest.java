package co.edu.corhuila.synkro.customers.application.usecase;

import co.edu.corhuila.synkro.customers.application.port.in.*;
import co.edu.corhuila.synkro.customers.application.port.in.CustomerUseCases.*;
import co.edu.corhuila.synkro.customers.application.port.out.CustomerRepository;
import co.edu.corhuila.synkro.customers.domain.model.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CustomerServiceTest {
    final Fake repository=new Fake();
    final CustomerService service=new CustomerService(repository);
    final Caller admin=new Caller(UUID.randomUUID().toString(),Set.of("ADMIN"),Set.of());
    final CustomerFields fields=new CustomerFields("Ana","doc",null,null,null);
    final UUID id=UUID.randomUUID();
    @Test void lifecycleUsesPortAndPreservesResource(){
        var created=service.create(admin,"key-12345",fields);assertThat(created.created()).isTrue();
        assertThat(service.create(admin,"key-12345",new CustomerFields("Other","different",null,null,null)).customer()).isEqualTo(created.customer());
        assertThat(service.get(admin,id)).isEqualTo(created.customer());
        var updated=service.update(admin,id,new CustomerFields("Eva","new",null,null,null));assertThat(updated.customerId()).isEqualTo(id);assertThat(updated.registrationDate()).isEqualTo(created.customer().registrationDate());
        var page=service.list(admin,new Query(null,null,1,20));assertThat(page.data()).containsExactly(updated);assertThat(page.totalPages()).isEqualTo(1);
        assertThat(service.deactivate(admin,id).active()).isFalse();assertThat(service.deactivate(admin,id).active()).isFalse();
        assertThatThrownBy(()->service.get(admin,id)).isInstanceOf(CustomerFailure.class);assertThatThrownBy(()->service.update(admin,id,fields)).isInstanceOf(CustomerFailure.class);
    }
    @Test void rejectsMissingResourcesAndKeys(){
        assertThatThrownBy(()->service.get(admin,id)).isInstanceOf(CustomerFailure.class);
        for(String key:Arrays.asList(null,"","short","x".repeat(129)))assertThatThrownBy(()->service.create(admin,key,fields)).isInstanceOf(CustomerFailure.class);
        assertThatThrownBy(()->new Query("",null,1,20)).isInstanceOf(CustomerFailure.class);
        assertThatThrownBy(()->new Query(null,null,0,20)).isInstanceOf(CustomerFailure.class);
        for(int limit:List.of(0,101))assertThatThrownBy(()->new Query(null,null,1,limit)).isInstanceOf(CustomerFailure.class);
        assertThat(new Query(null,null,Integer.MAX_VALUE,100).offset()).isEqualTo(214748364600L);
        assertThat(new Page(List.of(),0,1,20).totalPages()).isZero();
        assertThat(new Page(List.of(),21,1,20).totalPages()).isEqualTo(2);
    }
    @Test void authorizationPrecedesRepositoryAndReplay(){
        for(Caller caller:List.of(new Caller("synkro-worker",Set.of("SERVICE"),Set.of("customers:read")),new Caller("synkro-workflow",Set.of("SERVICE"),Set.of()),new Caller(id.toString(),Set.of("INVENTORY"),Set.of("customers:read")),new Caller("synkro-workflow",Set.of("ADMIN","SERVICE"),Set.of("customers:read")))){
            assertThatThrownBy(()->service.create(caller,"key-12345",fields)).isInstanceOf(CustomerFailure.class);
            assertThatThrownBy(()->service.get(caller,id)).isInstanceOf(CustomerFailure.class);
            assertThatThrownBy(()->service.list(caller,new Query(null,null,1,20))).isInstanceOf(CustomerFailure.class);
            assertThatThrownBy(()->service.update(caller,id,fields)).isInstanceOf(CustomerFailure.class);
            assertThatThrownBy(()->service.deactivate(caller,id)).isInstanceOf(CustomerFailure.class);
        }
        assertThat(repository.calls).isZero();
        service.create(admin,"key-12345",fields);
        var workflow=new Caller("synkro-workflow",Set.of("SERVICE"),Set.of("customers:read"));assertThat(service.get(workflow,id).active()).isTrue();
        assertThatThrownBy(()->service.create(workflow,"key-12345",fields)).isInstanceOf(CustomerFailure.class);
        assertThatThrownBy(()->service.list(workflow,new Query(null,null,1,20))).isInstanceOf(CustomerFailure.class);
        assertThatThrownBy(()->service.update(workflow,id,fields)).isInstanceOf(CustomerFailure.class);
        assertThatThrownBy(()->service.deactivate(workflow,id)).isInstanceOf(CustomerFailure.class);
        assertThat(service.get(new Caller(id.toString(),Set.of("SALESPERSON"),Set.of()),id)).isNotNull();
    }
    class Fake implements CustomerRepository {
        Customer customer;int calls;final Map<String,Customer> keys=new HashMap<>();
        public Created createOnce(String key,CustomerFields fields){calls++;if(keys.containsKey(key))return new Created(keys.get(key),false);customer=new Customer(id,fields,Instant.EPOCH,true);keys.put(key,customer);return new Created(customer,true);}
        public Optional<Customer> find(UUID id){calls++;return Optional.ofNullable(customer);}
        public Page list(Query q){calls++;return new Page(List.of(customer),1,q.page(),q.limit());}
        public Customer replace(UUID id,CustomerFields fields){calls++;return customer=customer.replace(fields);}
        public Customer deactivate(UUID id){calls++;return customer=customer.deactivate();}
        public boolean healthy(){return true;}
    }
}
