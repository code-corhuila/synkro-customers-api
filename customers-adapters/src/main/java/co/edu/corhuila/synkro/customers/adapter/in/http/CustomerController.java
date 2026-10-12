package co.edu.corhuila.synkro.customers.adapter.in.http;

import co.edu.corhuila.synkro.customers.application.port.in.CustomerUseCases;
import co.edu.corhuila.synkro.customers.domain.model.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {
    private final CustomerUseCases customers;
    public CustomerController(CustomerUseCases customers) { this.customers=customers; }
    public record Request(String name,String identityDocument,@Email String email,String phone,String address) {
        CustomerFields fields() {
            if(email!=null&&email.isEmpty())throw CustomerFailure.invalid("email");
            return new CustomerFields(name,identityDocument,email,phone,address);
        }
    }
    public record Response(UUID customerId,String name,String identityDocument,String email,String phone,String address,Instant registrationDate,boolean active) {
        static Response from(Customer c) { var f=c.fields();return new Response(c.customerId(),f.name(),f.identityDocument(),f.email(),f.phone(),f.address(),c.registrationDate(),c.active()); }
    }
    @PostMapping public ResponseEntity<Response> create(@AuthenticationPrincipal Jwt jwt,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Request request) {
        var result=customers.create(SecurityConfiguration.caller(jwt),key,request.fields());
        return result.created()?ResponseEntity.created(URI.create("/api/v1/customers/"+result.customer().customerId())).body(Response.from(result.customer())):
                ResponseEntity.ok(Response.from(result.customer()));
    }
    @GetMapping("/{id}") public Response get(@AuthenticationPrincipal Jwt jwt,@PathVariable String id) { return Response.from(customers.get(SecurityConfiguration.caller(jwt),uuid(id))); }
    @PutMapping("/{id}") public Response update(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@Valid @RequestBody Request request) { return Response.from(customers.update(SecurityConfiguration.caller(jwt),uuid(id),request.fields())); }
    @DeleteMapping("/{id}") public Response deactivate(@AuthenticationPrincipal Jwt jwt,@PathVariable String id) { return Response.from(customers.deactivate(SecurityConfiguration.caller(jwt),uuid(id))); }
    @GetMapping public Map<String,Object> list(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> params) {
        for(var entry:params.entrySet())if(!Set.of("identityDocument","active","page","limit").contains(entry.getKey())||entry.getValue().size()!=1)throw CustomerFailure.invalid(entry.getKey());
        String active=params.getFirst("active");if(active!=null&&!active.equals("true")&&!active.equals("false"))throw CustomerFailure.invalid("active");
        var query=new CustomerUseCases.Query(params.getFirst("identityDocument"),active==null?null:Boolean.valueOf(active),integer(params,"page",1),integer(params,"limit",20));
        var page=customers.list(SecurityConfiguration.caller(jwt),query);
        return Map.of("data",page.data().stream().map(Response::from).toList(),"meta",Map.of("page",page.page(),"limit",page.limit(),"total",page.total(),"totalPages",page.totalPages()));
    }
    private int integer(MultiValueMap<String,String> params,String field,int fallback) {
        String value=params.getFirst(field);if(value==null)return fallback;
        try { if(!value.matches("[0-9]+"))throw new NumberFormatException();return Integer.parseInt(value); }
        catch(NumberFormatException e){throw CustomerFailure.invalid(field);}
    }
    private UUID uuid(String id) {
        try { UUID value=UUID.fromString(id);if(!value.toString().equalsIgnoreCase(id))throw new IllegalArgumentException();return value; }
        catch(IllegalArgumentException e){throw CustomerFailure.invalid("id");}
    }
}
