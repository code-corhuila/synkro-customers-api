package co.edu.corhuila.synkro.customers.app;

import co.edu.corhuila.synkro.customers.application.port.in.CustomerUseCases.Query;
import co.edu.corhuila.synkro.customers.application.port.out.CustomerRepository;
import co.edu.corhuila.synkro.customers.domain.model.*;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class JdbcPersistenceTest {
    HikariDataSource datasource; CustomerRepository repository;
    String url=Objects.requireNonNull(System.getenv("TEST_DATABASE_URL"),"Mandatory disposable PostgreSQL fixture missing");
    @BeforeEach void connect(){var c=new CustomersComposition();datasource=c.datasource(url,"customers_app",System.getenv("TEST_DATABASE_PASSWORD"),"");repository=c.repository(datasource);}
    @AfterEach void close(){datasource.close();}
    String unique(){return UUID.randomUUID().toString().replace("-","").substring(0,25);}
    CustomerFields fields(String doc){return new CustomerFields("Synthetic JDBC",doc,null,null,null);}
    void admin(String query)throws Exception{try(var c=DriverManager.getConnection(url,"postgres",System.getenv("TEST_ADMIN_PASSWORD"));var s=c.createStatement()){s.execute(query);}}
    long count(String table,String column,String value)throws Exception{try(var c=datasource.getConnection();var s=c.prepareStatement("SELECT count(*) FROM customers_schema."+table+" WHERE "+column+"=?")){s.setString(1,value);try(var r=s.executeQuery()){r.next();return r.getLong(1);}}}
    @Test void lifecycleExactSearchReplacementAndSoftDeletion()throws Exception{
        String doc=unique(),key=unique();var first=repository.createOnce(key,fields(doc));var customer=first.customer();
        assertThat(first.created()).isTrue();assertThat(customer.active()).isTrue();assertThat(customer.fields().email()).isNull();assertThat(repository.healthy()).isTrue();
        var replay=repository.createOnce(key,fields(unique()));assertThat(replay.created()).isFalse();assertThat(replay.customer()).isEqualTo(customer);
        assertThat(repository.find(customer.customerId())).contains(customer);assertThat(repository.find(UUID.randomUUID())).isEmpty();
        var replacement=repository.replace(customer.customerId(),new CustomerFields("Updated",doc,"synthetic@example.test","123","Street"));
        assertThat(replacement.customerId()).isEqualTo(customer.customerId());assertThat(replacement.registrationDate()).isEqualTo(customer.registrationDate());
        assertThat(repository.replace(customer.customerId(),fields(doc)).fields().email()).isNull();
        assertThat(repository.list(new Query(doc,null,1,20)).total()).isEqualTo(1);assertThat(repository.list(new Query(doc.substring(0,5),null,1,20)).total()).isZero();
        var inactive=repository.deactivate(customer.customerId());assertThat(inactive.active()).isFalse();assertThat(repository.deactivate(customer.customerId())).isEqualTo(inactive);
        assertThat(repository.list(new Query(doc,false,1,20)).data()).containsExactly(inactive);assertThat(repository.list(new Query(doc,true,1,20)).data()).isEmpty();
        assertThatThrownBy(()->repository.replace(customer.customerId(),fields(doc))).isInstanceOf(CustomerFailure.class).hasMessage("Customer not found");
        assertThatThrownBy(()->repository.deactivate(UUID.randomUUID())).isInstanceOf(CustomerFailure.class);
        assertThatThrownBy(()->repository.createOnce(unique(),fields(doc))).isInstanceOf(CustomerFailure.class).extracting("code").isEqualTo("BUSINESS_RULE_VIOLATION");
        assertThat(count("customer","identity_document",doc)).isEqualTo(1);
    }
    @Test void concurrentReplayAndDuplicateDocumentRaces()throws Exception{
        var pool=Executors.newFixedThreadPool(8);String key=unique(),doc=unique();
        try{var jobs=new ArrayList<Callable<co.edu.corhuila.synkro.customers.application.port.in.CustomerUseCases.Created>>();for(int i=0;i<8;i++)jobs.add(()->repository.createOnce(key,fields(unique())));
            var results=pool.invokeAll(jobs);Set<UUID> ids=new HashSet<>();int created=0;for(var result:results){var value=result.get();ids.add(value.customer().customerId());if(value.created())created++;}assertThat(ids).hasSize(1);assertThat(created).isEqualTo(1);assertThat(count("idempotency_key","key",key)).isEqualTo(1);
            var races=pool.invokeAll(List.<Callable<String>>of(()->race(doc),()->race(doc)));assertThat(List.of(races.get(0).get(),races.get(1).get())).containsExactlyInAnyOrder("created","BUSINESS_RULE_VIOLATION");
        }finally{pool.shutdownNow();}
    }
    String race(String doc){try{repository.createOnce(unique(),fields(doc));return "created";}catch(CustomerFailure failure){return failure.code();}}
    @Test void failedKeyInsertionRollsBackBothRows()throws Exception{
        String key=unique(),doc=unique(),constraint="review_"+unique();
        admin("ALTER TABLE customers_schema.idempotency_key ADD CONSTRAINT "+constraint+" CHECK (key <> '"+key+"')");
        try{assertThatThrownBy(()->repository.createOnce(key,fields(doc))).isInstanceOf(CustomerFailure.class).hasMessage("Storage operation failed");assertThat(count("customer","identity_document",doc)).isZero();assertThat(count("idempotency_key","key",key)).isZero();}
        finally{admin("ALTER TABLE customers_schema.idempotency_key DROP CONSTRAINT "+constraint);}
    }
    @Test void restrictedLoginAndStorageFailureAreReal()throws Exception{
        try(var c=datasource.getConnection();var s=c.createStatement()){
            for(String query:List.of("CREATE TABLE customers_schema.forbidden(id int)","DELETE FROM customers_schema.customer","SELECT * FROM sentinel.private"))assertThatThrownBy(()->s.execute(query)).isInstanceOf(SQLException.class);
        }
        datasource.close();assertThat(repository.healthy()).isFalse();assertThatThrownBy(()->repository.find(UUID.randomUUID())).isInstanceOf(CustomerFailure.class).hasMessage("Storage operation failed");
    }
}
