package co.edu.corhuila.synkro.customers.adapter.in.http;

import co.edu.corhuila.synkro.customers.application.port.in.CustomerUseCases;
import co.edu.corhuila.synkro.customers.domain.model.*;
import jakarta.servlet.http.HttpServletRequest;
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
    /** Contacts are required (HU-CLI-05): every invalid field is reported together, nothing is trimmed or defaulted. */
    public record Request(String name,String identityDocument,String email,String phone,String address) {
        CustomerFields fields() { return CustomerFields.forWrite(name,identityDocument,email,phone,address); }
    }
    public record Response(UUID customerId,String name,String identityDocument,String email,String phone,String address,Instant registrationDate,boolean active) {
        static Response from(Customer c) { var f=c.fields();return new Response(c.customerId(),f.name(),f.identityDocument(),f.email(),f.phone(),f.address(),c.registrationDate(),c.active()); }
    }
    /** Only what a selector needs; no email, phone or address. */
    public record Suggestion(UUID customerId,String name,String identityDocument,boolean active,String match) {
        static Suggestion from(CustomerUseCases.Suggestion s) { return new Suggestion(s.customerId(),s.name(),s.identityDocument(),s.active(),s.match().name()); }
    }
    @PostMapping public ResponseEntity<Response> create(@AuthenticationPrincipal Jwt jwt,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody Request request) {
        var result=customers.create(SecurityConfiguration.caller(jwt),key,request.fields());
        return result.created()?ResponseEntity.created(URI.create("/api/v1/customers/"+result.customer().customerId())).body(Response.from(result.customer())):
                ResponseEntity.ok(Response.from(result.customer()));
    }
    @GetMapping("/{id}") public Response get(@AuthenticationPrincipal Jwt jwt,@PathVariable String id) { return Response.from(customers.get(SecurityConfiguration.caller(jwt),uuid(id))); }
    @PutMapping("/{id}") public Response update(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestBody Request request) { return Response.from(customers.update(SecurityConfiguration.caller(jwt),uuid(id),request.fields())); }
    @DeleteMapping("/{id}") public Response deactivate(@AuthenticationPrincipal Jwt jwt,@PathVariable String id) { return Response.from(customers.deactivate(SecurityConfiguration.caller(jwt),uuid(id))); }
    /** ADMIN-only transition; the operator is the validated JWT subject, never a request field. */
    @PostMapping("/{id}/activate") public Response activate(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,HttpServletRequest http) {
        return Response.from(customers.activate(SecurityConfiguration.caller(jwt),uuid(id),(String)http.getAttribute("correlationId")));
    }
    /** The query is personal data, so it travels in the body; nothing about it is logged by this service. */
    @PostMapping("/search") public Map<String,Object> search(@AuthenticationPrincipal Jwt jwt,@RequestBody Map<String,Object> body) {
        for(String key:body.keySet())if(!Set.of("query","status").contains(key))throw CustomerFailure.invalid(key,"unknown_property");
        if(!(body.get("query") instanceof String query))throw CustomerFailure.invalid("query","invalid_length");
        Object status=body.get("status");
        if(body.containsKey("status")&&!(status instanceof String))throw CustomerFailure.invalid("status","invalid_value");
        var result=customers.search(SecurityConfiguration.caller(jwt),CustomerUseCases.Search.of(query,(String)status));
        return Map.of("data",result.data().stream().map(Suggestion::from).toList(),
                "meta",Map.of("limit",CustomerUseCases.SearchResult.LIMIT,"hasMore",result.hasMore()));
    }
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
