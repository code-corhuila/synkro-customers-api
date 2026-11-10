package co.edu.corhuila.synkro.customers.application.usecase;

import co.edu.corhuila.synkro.customers.application.port.in.*;
import co.edu.corhuila.synkro.customers.application.port.in.CustomerUseCases.*;
import co.edu.corhuila.synkro.customers.application.port.out.CustomerRepository;
import co.edu.corhuila.synkro.customers.domain.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CustomerServiceTest {
    final Fake repository=new Fake();
    final CustomerService service=new CustomerService(repository);
    final Caller admin=new Caller(UUID.randomUUID().toString(),Set.of("ADMIN"),Set.of());
    final Caller seller=new Caller(UUID.randomUUID().toString(),Set.of("SALESPERSON"),Set.of());
    final CustomerFields fields=complete("Ana","doc");
    final UUID id=UUID.randomUUID();
    static CustomerFields complete(String name,String doc){return CustomerFields.forWrite(name,doc,"ana@example.test","3001234567","Calle 1");}
    @Test void lifecycleUsesPortAndPreservesResource(){
        var created=service.create(admin,"key-12345",fields);assertThat(created.created()).isTrue();
        assertThat(service.create(admin,"key-12345",complete("Other","different")).customer()).isEqualTo(created.customer());
        assertThat(service.get(admin,id)).isEqualTo(created.customer());
        var updated=service.update(admin,id,complete("Eva","new"));assertThat(updated.customerId()).isEqualTo(id);assertThat(updated.registrationDate()).isEqualTo(created.customer().registrationDate());
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
            assertThatThrownBy(()->service.search(caller,Search.of("ana",null))).isInstanceOf(CustomerFailure.class).extracting("code").isEqualTo("FORBIDDEN");
            assertThatThrownBy(()->service.activate(caller,id,"trace")).isInstanceOf(CustomerFailure.class).extracting("code").isEqualTo("FORBIDDEN");
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

    // ---- HU-CLI-05: contacts are required on every write, before any persistence call -------------------------------
    @Test void incompleteContactsNeverReachThePortOnCreateOrReplace(){
        var historical=new CustomerFields("Ana","doc",null,null,null);
        var invalidPhone=new CustomerFields("Ana","doc","ana@example.test","123","Calle 1");
        for(CustomerFields bad:List.of(historical,invalidPhone)){
            assertThatThrownBy(()->service.create(admin,"key-12345",bad)).isInstanceOf(CustomerFailure.class).extracting("code").isEqualTo("VALIDATION_ERROR");
            assertThatThrownBy(()->service.update(admin,id,bad)).isInstanceOf(CustomerFailure.class).extracting("code").isEqualTo("VALIDATION_ERROR");
        }
        assertThat(repository.calls).isZero();
        assertThat(repository.keys).isEmpty();
    }
    @Test void sellersAndAdminsKeepTheirExistingWritePermissions(){
        assertThat(service.create(seller,"key-seller1",fields).created()).isTrue();
        assertThat(service.update(seller,id,complete("Eva","new")).fields().name()).isEqualTo("Eva");
        assertThat(service.deactivate(seller,id).active()).isFalse();
    }
    @Test void aHistoricalCustomerStaysReadableAndDeactivatable(){
        repository.customer=new Customer(id,new CustomerFields("Ana","doc",null,null,null),Instant.EPOCH,true);
        assertThat(service.get(admin,id).fields().email()).isNull();
        assertThat(service.deactivate(seller,id).active()).isFalse();
    }

    // ---- HU-CLI-05: bounded search -----------------------------------------------------------------------------------
    static java.util.stream.Stream<String> invalidQueries(){
        return java.util.stream.Stream.of(null,""," ","   ","a"," a ","\t\n","  ","x".repeat(101)," "+"x".repeat(101)+" ");
    }
    @ParameterizedTest @MethodSource("invalidQueries")
    void invalidQueriesAreRejectedBeforeTheRepository(String text){
        assertThatThrownBy(()->Search.of(text,null)).isInstanceOf(CustomerFailure.class).extracting("code").isEqualTo("VALIDATION_ERROR");
        assertThat(repository.calls).isZero();
    }
    @Test void queryLimitsAreMeasuredAfterTrimmingInCodePoints(){
        assertThat(Search.of("ab",null).text()).isEqualTo("ab");
        assertThat(Search.of("   ab\t ",null).text()).isEqualTo("ab");
        assertThat(Search.of("x".repeat(100),null).text()).hasSize(100);
        assertThat(Search.of("🏠".repeat(100),null).text()).hasSize(200);       // 100 code points
        assertThatThrownBy(()->Search.of("🏠".repeat(101),null)).isInstanceOf(CustomerFailure.class);
        assertThatThrownBy(()->Search.of("🏠",null)).isInstanceOf(CustomerFailure.class);   // 1 code point
    }
    @Test void searchSplitsTokensOnAnyWhitespaceAndDefaultsToActive(){
        var search=Search.of("  perez\t ana maria ",null);
        assertThat(search.tokens()).containsExactly("perez","ana","maria");
        assertThat(search.status()).isEqualTo(Search.Status.ACTIVE);
        assertThat(Search.of("ana","ACTIVE").status()).isEqualTo(Search.Status.ACTIVE);
        assertThat(Search.of("ana","INACTIVE").status()).isEqualTo(Search.Status.INACTIVE);
    }
    @ParameterizedTest @ValueSource(strings={"","active","Inactive","ALL","ANY","true","0"," ACTIVE"})
    void unknownStatusesAreRejected(String status){
        assertThatThrownBy(()->Search.of("ana",status)).isInstanceOf(CustomerFailure.class)
                .satisfies(e->assertThat(((CustomerFailure)e).field()).isEqualTo("status"));
    }
    @Test void bothSalesRolesSearchActiveCustomersButOnlyAdminSearchesInactiveOnes(){
        for(Caller caller:List.of(admin,seller))assertThat(service.search(caller,Search.of("ana",null)).data()).isEmpty();
        assertThat(service.search(admin,Search.of("ana","INACTIVE")).hasMore()).isFalse();
        assertThat(repository.searches).hasSize(3);
        assertThatThrownBy(()->service.search(seller,Search.of("ana","INACTIVE"))).isInstanceOf(CustomerFailure.class).extracting("code").isEqualTo("FORBIDDEN");
        assertThat(repository.searches).hasSize(3);   // the denied one never reached the port
    }
    @Test void searchResultIsBoundedToFive(){
        assertThat(CustomerUseCases.SearchResult.LIMIT).isEqualTo(5);
        var six=java.util.stream.IntStream.range(0,6).mapToObj(i->new CustomerUseCases.Suggestion(UUID.randomUUID(),"n"+i,"d"+i,true,CustomerUseCases.Suggestion.Match.NAME)).toList();
        assertThatThrownBy(()->new CustomerUseCases.SearchResult(six,true)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- HU-CLI-05: ADMIN-only activation --------------------------------------------------------------------------
    @Test void onlyAHumanAdminActivatesAndIsRecordedAsOperator(){
        repository.customer=new Customer(id,fields,Instant.EPOCH,false);
        assertThatThrownBy(()->service.activate(seller,id,"trace")).isInstanceOf(CustomerFailure.class).extracting("code").isEqualTo("FORBIDDEN");
        assertThat(repository.activations).isEmpty();
        var result=service.activate(admin,id,"trace-1");
        assertThat(result.active()).isTrue();assertThat(result.customerId()).isEqualTo(id);
        assertThat(repository.activations).containsExactly(List.of(id,UUID.fromString(admin.subject()),"trace-1"));
        assertThat(service.activate(admin,id,"trace-2").active()).isTrue();   // repeat is safe, answered by the port
    }
    @Test void activationRejectsANonUuidOperatorAndTruncatesAnOversizedCorrelationId(){
        repository.customer=new Customer(id,fields,Instant.EPOCH,false);
        assertThatThrownBy(()->service.activate(new Caller("not-a-uuid",Set.of("ADMIN"),Set.of()),id,"t")).isInstanceOf(CustomerFailure.class).extracting("code").isEqualTo("FORBIDDEN");
        service.activate(admin,id,"t".repeat(300));
        assertThat((String)repository.activations.get(0).get(2)).hasSize(128);
        repository.customer=new Customer(id,fields,Instant.EPOCH,false);
        service.activate(admin,id,null);
        assertThat(repository.activations.get(1).get(2)).isNull();
    }
    @Test void activatingAnUnknownCustomerIsNotFound(){
        assertThatThrownBy(()->service.activate(admin,id,"t")).isInstanceOf(CustomerFailure.class).extracting("code").isEqualTo("NOT_FOUND");
    }

    class Fake implements CustomerRepository {
        Customer customer;int calls;final Map<String,Customer> keys=new HashMap<>();
        final List<Search> searches=new ArrayList<>();final List<List<Object>> activations=new ArrayList<>();
        public Created createOnce(String key,CustomerFields fields){calls++;if(keys.containsKey(key))return new Created(keys.get(key),false);customer=new Customer(id,fields,Instant.EPOCH,true);keys.put(key,customer);return new Created(customer,true);}
        public Optional<Customer> find(UUID id){calls++;return Optional.ofNullable(customer);}
        public Page list(Query q){calls++;return new Page(List.of(customer),1,q.page(),q.limit());}
        public Customer replace(UUID id,CustomerFields fields){calls++;return customer=customer.replace(fields);}
        public Customer deactivate(UUID id){calls++;return customer=customer.deactivate();}
        public SearchResult search(Search search){searches.add(search);return new SearchResult(List.of(),false);}
        public Customer activate(UUID id,UUID operator,String correlationId){
            if(customer==null)throw CustomerFailure.missing();
            if(!customer.active())activations.add(Arrays.asList(id,operator,correlationId));
            return customer=customer.activate();
        }
        public boolean healthy(){return true;}
    }
}
