package co.edu.corhuila.synkro.customers.app;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import java.net.URI;
import java.net.http.*;
import java.security.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class LifecycleHttpTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final KeyPair KEYS=keys();
    static final String HUMAN=UUID.randomUUID().toString();
    @LocalServerPort int port;
    final HttpClient client=HttpClient.newHttpClient();
    static KeyPair keys(){try{var g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);return g.generateKeyPair();}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){
        p.add("JWT_PUBLIC_KEY",()->"-----BEGIN PUBLIC KEY-----\n"+Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----");
        p.add("SPRING_DATASOURCE_URL",()->Objects.requireNonNull(System.getenv("TEST_DATABASE_URL"),"Mandatory disposable PostgreSQL fixture missing"));
        p.add("SPRING_DATASOURCE_USERNAME",()->"customers_app");p.add("SPRING_DATASOURCE_PASSWORD",()->System.getenv("TEST_DATABASE_PASSWORD"));
    }
    Map<String,Object> claims(String sub,String role,List<String> permissions){return new LinkedHashMap<>(Map.of("sub",sub,"roles",List.of(role),"permissions",permissions,"iat",Instant.now().getEpochSecond()-60,"exp",Instant.now().getEpochSecond()+3600,"jti",UUID.randomUUID().toString()));}
    String signed(Map<String,Object> claims,KeyPair key,String alg)throws Exception{
        var b=Base64.getUrlEncoder().withoutPadding();String input=b.encodeToString(JSON.writeValueAsBytes(Map.of("alg",alg)))+"."+b.encodeToString(JSON.writeValueAsBytes(claims));
        var s=Signature.getInstance("SHA256withRSA");s.initSign(key.getPrivate());s.update(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));return input+"."+b.encodeToString(s.sign());
    }
    String token(String sub,String role,List<String> permissions)throws Exception{return signed(claims(sub,role,permissions),KEYS,"RS256");}
    String admin()throws Exception{return token(HUMAN,"ADMIN",List.of());}
    String unique(){return UUID.randomUUID().toString().substring(0,25);}
    String body(String doc)throws Exception{return JSON.writeValueAsString(Map.of("name","Cliente sintético","identityDocument",doc));}
    HttpResponse<String> request(String method,String path,String token,String body,String key)throws Exception{
        var r=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(java.time.Duration.ofSeconds(20)).header("X-Correlation-Id","test-correlation");
        if(token!=null)r.header("Authorization","Bearer "+token);if(key!=null)r.header("Idempotency-Key",key);if(body!=null)r.header("Content-Type","application/json");
        return client.send(r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode json(HttpResponse<String> r)throws Exception{return JSON.readTree(r.body());}
    String id(HttpResponse<String> r)throws Exception{return json(r).get("customerId").asText();}
    HttpResponse<String> create(String doc,String key)throws Exception{return request("POST","/api/v1/customers",admin(),body(doc),key);}
    Connection db(boolean admin)throws Exception{return DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"),admin?"postgres":"customers_app",System.getenv(admin?"TEST_ADMIN_PASSWORD":"TEST_DATABASE_PASSWORD"));}
    long count(String table,String column,String value)throws Exception{try(var c=db(false);var s=c.prepareStatement("SELECT count(*) FROM customers_schema."+table+" WHERE "+column+"=?")){s.setString(1,value);try(var r=s.executeQuery()){r.next();return r.getLong(1);}}}

    @Test void creationReplayReplacementAndSoftDeletion()throws Exception{
        String doc=unique(),key=unique();var first=create(doc,key);assertThat(first.statusCode()).isEqualTo(201);String id=id(first),path="/api/v1/customers/"+id;
        assertThat(first.headers().firstValue("Location")).hasValue(path);assertThat(json(first).get("active").asBoolean()).isTrue();assertThat(json(first).get("email").isNull()).isTrue();assertThat(json(first).has("registrationDate")).isTrue();assertThat(json(first).has("registeredAt")).isFalse();
        var replay=create(unique(),key);assertThat(replay.statusCode()).isEqualTo(200);assertThat(json(replay)).isEqualTo(json(first));assertThat(count("customer","identity_document",doc)).isEqualTo(1);assertThat(count("idempotency_key","key",key)).isEqualTo(1);
        assertThat(request("GET",path,admin(),null,null).statusCode()).isEqualTo(200);
        var full=request("PUT",path,admin(),"{\"name\":\"Eva\",\"identityDocument\":\""+doc+"\",\"email\":\"eva@example.test\",\"phone\":\"123\",\"address\":\"Street\"}",null);assertThat(full.statusCode()).isEqualTo(200);assertThat(json(full).get("email").asText()).isEqualTo("eva@example.test");
        var updated=request("PUT",path,admin(),body(doc),null);assertThat(updated.statusCode()).isEqualTo(200);assertThat(id(updated)).isEqualTo(id);assertThat(json(updated).get("registrationDate")).isEqualTo(json(first).get("registrationDate"));assertThat(json(updated).get("email").isNull()).isTrue();
        var deleted=request("DELETE",path,admin(),null,null);assertThat(deleted.statusCode()).isEqualTo(200);assertThat(json(deleted).get("active").asBoolean()).isFalse();assertThat(json(request("DELETE",path,admin(),null,null))).isEqualTo(json(deleted));
        assertThat(request("GET",path,admin(),null,null).statusCode()).isEqualTo(404);assertThat(request("PUT",path,admin(),body(doc),null).statusCode()).isEqualTo(404);assertThat(count("customer","identity_document",doc)).isEqualTo(1);assertThat(json(create(doc,key)).get("active").asBoolean()).isFalse();
    }
    @Test void concurrentSameKeyDifferentAndSameBodiesConverge()throws Exception{
        for(boolean same:List.of(true,false)){String key=unique(),doc=unique();var pool=Executors.newFixedThreadPool(8);
            try{List<Callable<HttpResponse<String>>> jobs=new ArrayList<>();for(int i=0;i<8;i++)jobs.add(()->create(same?doc:unique(),key));Set<String> ids=new HashSet<>();int created=0;
                for(var f:pool.invokeAll(jobs)){var r=f.get();assertThat(r.statusCode()).isIn(200,201);ids.add(id(r));if(r.statusCode()==201)created++;}assertThat(created).isEqualTo(1);assertThat(ids).hasSize(1);assertThat(count("idempotency_key","key",key)).isEqualTo(1);
            }finally{pool.shutdownNow();}}
    }
    @Test void duplicateDocumentActiveInactiveAndConcurrentKeys()throws Exception{
        String doc=unique();var owner=create(doc,unique());var duplicate=create(doc,unique());assertThat(duplicate.statusCode()).isEqualTo(422);assertThat(json(duplicate).get("error").asText()).isEqualTo("BUSINESS_RULE_VIOLATION");assertThat(duplicate.body()).contains("identityDocument");
        var other=create(unique(),unique());assertThat(request("PUT","/api/v1/customers/"+id(other),admin(),body(doc),null).statusCode()).isEqualTo(422);request("DELETE","/api/v1/customers/"+id(owner),admin(),null,null);assertThat(create(doc,unique()).statusCode()).isEqualTo(422);
        String raceDoc=unique();var pool=Executors.newFixedThreadPool(2);try{var results=pool.invokeAll(List.<Callable<Integer>>of(()->create(raceDoc,unique()).statusCode(),()->create(raceDoc,unique()).statusCode()));assertThat(List.of(results.get(0).get(),results.get(1).get())).containsExactlyInAnyOrder(201,422);}finally{pool.shutdownNow();}
    }
    @Test void searchPaginationStableOrderingAndInvalidQueries()throws Exception{
        String doc=unique(),path="/api/v1/customers";var first=create(doc,unique());request("DELETE",path+"/"+id(first),admin(),null,null);
        var list=json(request("GET",path+"?identityDocument="+doc,admin(),null,null));assertThat(list.get("data").size()).isEqualTo(1);assertThat(list.at("/data/0/active").asBoolean()).isFalse();
        assertThat(json(request("GET",path+"?identityDocument="+doc+"&active=true",admin(),null,null)).get("data").size()).isZero();assertThat(json(request("GET",path+"?identityDocument="+doc+"&active=false",admin(),null,null)).get("data").size()).isEqualTo(1);assertThat(json(request("GET",path+"?identityDocument="+doc.substring(0,10),admin(),null,null)).get("data").size()).isZero();
        var defaults=json(request("GET",path,admin(),null,null));assertThat(defaults.at("/meta/page").asInt()).isEqualTo(1);assertThat(defaults.at("/meta/limit").asInt()).isEqualTo(20);assertThat(json(request("GET",path+"?limit=1&page=2",admin(),null,null)).get("data").size()).isEqualTo(1);
        String a=id(create(unique(),unique())),b=id(create(unique(),unique()));try(var c=db(true);var s=c.prepareStatement("UPDATE customers_schema.customer SET registration_date='2099-01-01' WHERE customer_id IN (?::uuid,?::uuid)")){s.setString(1,a);s.setString(2,b);s.executeUpdate();}
        assertThat(json(request("GET",path+"?limit=2",admin(),null,null)).at("/data/0/customerId").asText()).isEqualTo(a.compareTo(b)>0?a:b);
        for(String q:List.of("foo=1","page=0","page=-1","page=1.1","page=x","limit=0","limit=101","active=1","active=TRUE","active=","identityDocument=","identityDocument="+"x".repeat(31),"limit=1&limit=2"))assertThat(request("GET",path+"?"+q,admin(),null,null).statusCode()).as(q).isEqualTo(400);
    }
    @Test void payloadAndIdValidation()throws Exception{
        for(String payload:List.of("{}","{","[]","{\"name\":12,\"identityDocument\":\"a\"}","{\"name\":\"Ana\",\"identityDocument\":false}","{\"name\":\"Ana\",\"identityDocument\":\"a\",\"email\":\"invalid\"}","{\"name\":\"\",\"identityDocument\":\"a\"}","{\"name\":\"Ana\",\"identityDocument\":\""+"x".repeat(31)+"\"}"))assertThat(request("POST","/api/v1/customers",admin(),payload,unique()).statusCode()).as(payload).isEqualTo(400);
        for(String key:List.of("short","x".repeat(129)))assertThat(create(unique(),key).statusCode()).isEqualTo(400);assertThat(create(unique(),null).statusCode()).isEqualTo(400);assertThat(request("GET","/api/v1/customers/not-uuid",admin(),null,null).statusCode()).isEqualTo(400);
        for(String method:List.of("GET","PUT","DELETE"))assertThat(request(method,"/api/v1/customers/"+UUID.randomUUID(),admin(),method.equals("PUT")?body(unique()):null,null).statusCode()).isEqualTo(404);
    }
    @Test void signedAuthenticationAndFullRoleServiceMatrix()throws Exception{
        String path="/api/v1/customers/"+id(create(unique(),unique()));assertThat(request("GET",path,null,null,null).statusCode()).isEqualTo(401);
        for(String bad:List.of("not-a-token",signed(claims(HUMAN,"ADMIN",List.of()),KEYS,"none"),signed(claims(HUMAN,"ADMIN",List.of()),KEYS,"HS256"),signed(claims(HUMAN,"ADMIN",List.of()),KEYS,"RS512"),signed(claims(HUMAN,"ADMIN",List.of()),keys(),"RS256")))assertThat(request("GET",path,bad,null,null).statusCode()).isEqualTo(401);
        var good=claims(HUMAN,"ADMIN",List.of());for(String field:good.keySet()){var c=new LinkedHashMap<>(good);c.remove(field);assertThat(request("GET",path,signed(c,KEYS,"RS256"),null,null).statusCode()).as(field).isEqualTo(401);}
        for(var e:Map.<String,Object>of("exp",Instant.now().getEpochSecond()-60,"sub","not-uuid","roles","ADMIN","permissions",List.of(1),"iat",Instant.now().getEpochSecond()+3600).entrySet()){var c=new LinkedHashMap<>(good);c.put(e.getKey(),e.getValue());assertThat(request("GET",path,signed(c,KEYS,"RS256"),null,null).statusCode()).isEqualTo(401);}
        for(String role:List.of("ADMIN","SALESPERSON")){String t=token(HUMAN,role,List.of());assertThat(request("GET",path,t,null,null).statusCode()).isEqualTo(200);assertThat(request("GET","/api/v1/customers",t,null,null).statusCode()).isEqualTo(200);}
        for(String t:List.of(token(HUMAN,"INVENTORY",List.of("customers:read")),token("synkro-worker","SERVICE",List.of("customers:read")),token("synkro-workflow","SERVICE",List.of())))for(String method:List.of("GET","POST","PUT","DELETE"))assertThat(request(method,method.equals("POST")?"/api/v1/customers":path,t,method.equals("POST")||method.equals("PUT")?body(unique()):null,unique()).statusCode()).isEqualTo(403);
        String workflow=token("synkro-workflow","SERVICE",List.of("customers:read"));assertThat(request("GET",path,workflow,null,null).statusCode()).isEqualTo(200);assertThat(request("GET","/api/v1/customers",workflow,null,null).statusCode()).isEqualTo(403);
        for(String method:List.of("POST","PUT","DELETE"))assertThat(request(method,method.equals("POST")?"/api/v1/customers":path,workflow,method.equals("DELETE")?null:body(unique()),unique()).statusCode()).isEqualTo(403);
        request("DELETE",path,admin(),null,null);assertThat(request("GET",path,workflow,null,null).statusCode()).isEqualTo(404);assertThat(request("GET","/api/v1/customers/"+UUID.randomUUID(),workflow,null,null).statusCode()).isEqualTo(404);
        String key=unique(),doc=unique();create(doc,key);assertThat(request("POST","/api/v1/customers",workflow,body(doc),key).statusCode()).isEqualTo(403);
    }
    @Test void bothHumanRolesCanWriteAndErrorsGenerateCorrelation()throws Exception{
        for(String role:List.of("ADMIN","SALESPERSON")){
            String t=token(HUMAN,role,List.of()),doc=unique();
            var created=request("POST","/api/v1/customers",t,body(doc),unique());assertThat(created.statusCode()).isEqualTo(201);
            String path="/api/v1/customers/"+id(created);assertThat(request("PUT",path,t,body(unique()),null).statusCode()).isEqualTo(200);assertThat(request("DELETE",path,t,null,null).statusCode()).isEqualTo(200);
        }
        var r=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/customers")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(401);assertThat(r.headers().firstValue("X-Correlation-Id")).hasValue(json(r).get("traceId").asText());assertThat(UUID.fromString(json(r).get("traceId").asText())).isNotNull();
        assertThat(request("PATCH","/api/v1/customers/"+UUID.randomUUID(),admin(),null,null).statusCode()).isEqualTo(405);
        for(String field:List.of("name","identityDocument","email","phone","address")){
            var fields=new LinkedHashMap<String,Object>(Map.of("name","Ana","identityDocument",unique()));fields.put(field,"x".repeat(field.equals("name")?151:field.equals("identityDocument")?31:field.equals("phone")?21:256));
            assertThat(request("POST","/api/v1/customers",admin(),JSON.writeValueAsString(fields),unique()).statusCode()).as(field).isEqualTo(400);
        }
    }
    @Test void atomicRollbackSafeErrorsRestrictedLoginAndHealth()throws Exception{
        String doc=unique(),key=unique();try(var c=db(true);var s=c.createStatement()){s.execute("ALTER TABLE customers_schema.idempotency_key ADD CONSTRAINT test_failure CHECK (key <> '"+key+"')");}
        try{var f=create(doc,key);assertThat(f.statusCode()).isEqualTo(500);assertThat(json(f).get("traceId").asText()).isEqualTo("test-correlation");assertThat(f.body()).doesNotContain(doc,key,"SQL","postgres","constraint");assertThat(count("customer","identity_document",doc)).isZero();assertThat(count("idempotency_key","key",key)).isZero();}finally{try(var c=db(true);var s=c.createStatement()){s.execute("ALTER TABLE customers_schema.idempotency_key DROP CONSTRAINT test_failure");}}
        try(var c=db(false);var s=c.createStatement()){try(var r=s.executeQuery("SELECT current_user")){r.next();assertThat(r.getString(1)).isEqualTo("customers_app");}for(String sql:List.of("CREATE TABLE customers_schema.forbidden(id int)","DELETE FROM customers_schema.customer","SELECT * FROM customers_schema.flyway_schema_history","SELECT * FROM sentinel.private"))assertThatThrownBy(()->s.execute(sql)).isInstanceOf(SQLException.class);}
        var health=request("GET","/health",null,null,null);assertThat(health.statusCode()).isEqualTo(200);assertThat(json(health).at("/dependencies/database").asText()).isEqualTo("ok");
        for(String path:List.of("/unknown","/api/v1/customers/"+UUID.randomUUID())){var r=request("GET",path,admin(),null,null);assertThat(r.statusCode()).isEqualTo(404);assertThat(json(r).get("error").asText()).isEqualTo("NOT_FOUND");assertThat(r.headers().firstValue("X-Correlation-Id")).hasValue("test-correlation");}
        var bad=request("POST","/api/v1/customers",admin(),"{",unique());assertThat(json(bad).get("error").asText()).isEqualTo("VALIDATION_ERROR");assertThat(json(bad).get("traceId").asText()).isEqualTo("test-correlation");
    }
    @Test void externalKeyRaceRollsBackBeforeReadingWinner()throws Exception{
        String winner=id(create(unique(),unique())),key=unique(),doc=unique();
        try(var c=db(true);var s=c.createStatement()){
            s.execute("CREATE FUNCTION customers_schema.test_key_race() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.key='"+key+"' AND NEW.customer_id <> '"+winner+"'::uuid THEN PERFORM pg_sleep(2); END IF; RETURN NEW; END $$");
            s.execute("CREATE TRIGGER test_key_race BEFORE INSERT ON customers_schema.idempotency_key FOR EACH ROW EXECUTE FUNCTION customers_schema.test_key_race()");
        }
        var pool=Executors.newSingleThreadExecutor();
        try{
            var pending=pool.submit(()->create(doc,key));boolean sleeping=false;
            for(int i=0;i<100;i++){
                try(var c=db(true);var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE application_name='synkro-customers-api' AND wait_event='PgSleep'")){r.next();if(r.getInt(1)>0){sleeping=true;break;}}
                Thread.sleep(20);
            }
            assertThat(sleeping).as("API reached test-only key-insertion barrier").isTrue();
            try(var c=db(true);var s=c.prepareStatement("INSERT INTO customers_schema.idempotency_key(key,customer_id) VALUES (?,?::uuid)")){s.setString(1,key);s.setString(2,winner);s.executeUpdate();}
            var replay=pending.get(10,TimeUnit.SECONDS);assertThat(replay.statusCode()).isEqualTo(200);assertThat(id(replay)).isEqualTo(winner);
            assertThat(count("customer","identity_document",doc)).isZero();assertThat(count("idempotency_key","key",key)).isEqualTo(1);
        }finally{
            pool.shutdownNow();try(var c=db(true);var s=c.createStatement()){s.execute("DROP TRIGGER test_key_race ON customers_schema.idempotency_key");s.execute("DROP FUNCTION customers_schema.test_key_race()");}
        }
    }
}
