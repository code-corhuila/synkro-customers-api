package co.edu.corhuila.synkro.customers.app;

import co.edu.corhuila.synkro.customers.application.port.in.CustomerUseCases;
import co.edu.corhuila.synkro.customers.application.port.out.CustomerRepository;
import co.edu.corhuila.synkro.customers.application.usecase.CustomerService;
import co.edu.corhuila.synkro.customers.adapter.out.persistence.JdbcCustomerRepository;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.cfg.*;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.zaxxer.hikari.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;

@Configuration
public class CustomersComposition {
    @Bean(destroyMethod="close") public HikariDataSource datasource(@Value("${SPRING_DATASOURCE_URL}") String url,
            @Value("${SPRING_DATASOURCE_USERNAME:customers_app}") String user,@Value("${SPRING_DATASOURCE_PASSWORD:}") String password,
            @Value("${SPRING_DATASOURCE_PASSWORD_FILE:}") String file) {
        validateUrl(url,user);
        try { if(!file.isBlank())password=Files.readString(Path.of(file)).replaceFirst("[\\r\\n]+$",""); }
        catch(Exception e){throw new IllegalArgumentException("Database secret file cannot be read");}
        if(password.isEmpty())throw new IllegalArgumentException("Customers database credential required");
        var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername(user);config.setPassword(password);config.setMaximumPoolSize(10);config.setMinimumIdle(0);
        config.setConnectionTimeout(5000);config.setValidationTimeout(2000);config.setInitializationFailTimeout(-1);config.setConnectionInitSql("SET statement_timeout=5000");
        config.addDataSourceProperty("connectTimeout","3");config.addDataSourceProperty("loginTimeout","5");config.addDataSourceProperty("socketTimeout","10");config.addDataSourceProperty("currentSchema","customers_schema");config.addDataSourceProperty("ApplicationName","synkro-customers-api");
        return new HikariDataSource(config);
    }
    @Bean public CustomerRepository repository(HikariDataSource datasource) {
        var tx=new TransactionTemplate(new DataSourceTransactionManager(datasource));tx.setTimeout(10);
        return new JdbcCustomerRepository(new JdbcTemplate(datasource),tx);
    }
    @Bean public CustomerUseCases useCases(CustomerRepository repository) { return new CustomerService(repository); }
    private void validateUrl(String url,String user) {
        if(!user.equals("customers_app")||!url.matches("jdbc:postgresql://[a-zA-Z0-9._-]+:[0-9]{1,5}/[a-zA-Z0-9_-]+(\\?[^\\s]+)?"))throw new IllegalArgumentException("Restricted Customers database configuration required");
        if(!url.contains("?"))return;
        Set<String> seen=new HashSet<>();
        for(String entry:url.substring(url.indexOf('?')+1).split("&",-1)){
            String[] parts=entry.split("=",-1);if(parts.length!=2||!seen.add(parts[0]))throw new IllegalArgumentException("Invalid JDBC properties");
            boolean valid=switch(parts[0]){
                case "currentSchema" -> parts[1].equals("customers_schema");
                case "sslmode" -> Set.of("disable","prefer","require","verify-ca","verify-full").contains(parts[1]);
                case "connectTimeout","loginTimeout","socketTimeout" -> bounded(parts[1],parts[0].equals("connectTimeout")?3:parts[0].equals("loginTimeout")?5:10);
                default -> false;
            };
            if(!valid)throw new IllegalArgumentException("Invalid JDBC properties");
        }
    }
    private boolean bounded(String value,int max) { try { int n=Integer.parseInt(value);return n>=1&&n<=max; } catch(NumberFormatException e){return false;} }
    @Bean public Clock clock() { return Clock.systemUTC(); }
    @Bean public Jackson2ObjectMapperBuilderCustomizer strictJson() {
        return builder -> builder.featuresToDisable(MapperFeature.ALLOW_COERCION_OF_SCALARS).postConfigurer(mapper -> {
            for(var shape:new CoercionInputShape[]{CoercionInputShape.Integer,CoercionInputShape.Float,CoercionInputShape.Boolean})
                mapper.coercionConfigFor(LogicalType.Textual).setCoercion(shape,CoercionAction.Fail);
        });
    }
}
