package co.edu.corhuila.synkro.customers.app;

import com.fasterxml.jackson.databind.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.URI;
import java.net.http.*;
import java.security.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * Real HTTP server + real disposable PostgreSQL (TEST_DATABASE_URL) + locally verified RS256 tokens signed with a
 * throw-away key. Shared by the HU-CLI-05 test classes so they reuse one application context. Synthetic data only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class HttpTestSupport {
    static final ObjectMapper JSON = new ObjectMapper();
    static final KeyPair KEYS = keys();
    static final String ADMIN_ID = UUID.randomUUID().toString(), SELLER_ID = UUID.randomUUID().toString();
    @LocalServerPort int port;
    final HttpClient client = HttpClient.newHttpClient();

    static KeyPair keys() { try { var g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048); return g.generateKeyPair(); } catch (Exception e) { throw new IllegalStateException(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("JWT_PUBLIC_KEY", () -> "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----");
        p.add("SPRING_DATASOURCE_URL", () -> Objects.requireNonNull(System.getenv("TEST_DATABASE_URL"), "Mandatory disposable PostgreSQL fixture missing"));
        p.add("SPRING_DATASOURCE_USERNAME", () -> "customers_app"); p.add("SPRING_DATASOURCE_PASSWORD", () -> System.getenv("TEST_DATABASE_PASSWORD"));
    }

    // ---- credentials -------------------------------------------------------------------------------------------------
    String token(String sub, String role, List<String> permissions) throws Exception {
        var claims = new LinkedHashMap<String, Object>(Map.of("sub", sub, "roles", List.of(role), "permissions", permissions,
                "iat", Instant.now().getEpochSecond() - 60, "exp", Instant.now().getEpochSecond() + 3600, "jti", UUID.randomUUID().toString()));
        var b = Base64.getUrlEncoder().withoutPadding();
        String input = b.encodeToString(JSON.writeValueAsBytes(Map.of("alg", "RS256"))) + "." + b.encodeToString(JSON.writeValueAsBytes(claims));
        var s = Signature.getInstance("SHA256withRSA"); s.initSign(KEYS.getPrivate()); s.update(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return input + "." + b.encodeToString(s.sign());
    }
    String admin() throws Exception { return token(ADMIN_ID, "ADMIN", List.of()); }
    String seller() throws Exception { return token(SELLER_ID, "SALESPERSON", List.of()); }
    String inventory() throws Exception { return token(UUID.randomUUID().toString(), "INVENTORY", List.of("customers:read")); }
    String workflow() throws Exception { return token("synkro-workflow", "SERVICE", List.of("customers:read")); }

    // ---- requests ----------------------------------------------------------------------------------------------------
    HttpResponse<String> request(String method, String path, String token, String body, String key) throws Exception { return request(method, path, token, body, key, "test-correlation"); }
    HttpResponse<String> request(String method, String path, String token, String body, String key, String correlation) throws Exception {
        var r = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(java.time.Duration.ofSeconds(20));
        if (correlation != null) r.header("X-Correlation-Id", correlation);
        if (token != null) r.header("Authorization", "Bearer " + token);
        if (key != null) r.header("Idempotency-Key", key);
        if (body != null) r.header("Content-Type", "application/json");
        return client.send(r.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode json(HttpResponse<String> r) throws Exception { return JSON.readTree(r.body()); }
    String id(HttpResponse<String> r) throws Exception { return json(r).get("customerId").asText(); }
    String unique() { return UUID.randomUUID().toString().substring(0, 25); }
    /** Ten synthetic digits, unique enough for documents/phones in one test run. */
    String digits() { return String.format("%010d", Math.abs(UUID.randomUUID().getLeastSignificantBits() % 10_000_000_000L)); }

    String body(String name, String doc) throws Exception {
        return JSON.writeValueAsString(Map.of("name", name, "identityDocument", doc, "email", "cliente@example.test", "phone", "3001234567", "address", "Calle 1 # 2-3"));
    }
    String body(String doc) throws Exception { return body("Cliente sintético", doc); }
    HttpResponse<String> create(String name, String doc) throws Exception { return request("POST", "/api/v1/customers", admin(), body(name, doc), unique()); }
    HttpResponse<String> create(String doc) throws Exception { return create("Cliente sintético", doc); }
    HttpResponse<String> search(String token, Map<String, ?> body) throws Exception { return request("POST", "/api/v1/customers/search", token, JSON.writeValueAsString(body), null); }
    HttpResponse<String> search(String token, String query) throws Exception { return search(token, Map.of("query", query)); }
    List<String> ids(HttpResponse<String> response) throws Exception {
        var out = new ArrayList<String>(); for (var n : json(response).get("data")) out.add(n.get("customerId").asText()); return out;
    }
    void deactivate(String id) throws Exception { assertStatus(request("DELETE", "/api/v1/customers/" + id, admin(), null, null), 200); }
    static void assertStatus(HttpResponse<String> r, int expected) { if (r.statusCode() != expected) throw new AssertionError("Expected " + expected + " but was " + r.statusCode() + " for " + r.uri().getPath()); }

    // ---- database ----------------------------------------------------------------------------------------------------
    Connection db(boolean admin) throws Exception {
        return DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"), admin ? "postgres" : "customers_app", System.getenv(admin ? "TEST_ADMIN_PASSWORD" : "TEST_DATABASE_PASSWORD"));
    }
    long count(String table, String column, String value) throws Exception {
        try (var c = db(false); var s = c.prepareStatement("SELECT count(*) FROM customers_schema." + table + " WHERE " + column + "=?")) {
            s.setString(1, value); try (var r = s.executeQuery()) { r.next(); return r.getLong(1); }
        }
    }
    long audits(String customerId) throws Exception {
        try (var c = db(false); var s = c.prepareStatement("SELECT count(*) FROM customers_schema.customer_audit WHERE customer_id=?::uuid")) {
            s.setString(1, customerId); try (var r = s.executeQuery()) { r.next(); return r.getLong(1); }
        }
    }
    void admin(String sql, Object... args) throws Exception {
        try (var c = db(true); var s = c.prepareStatement(sql)) { for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]); s.execute(); }
    }
    /** A customer as stored before HU-CLI-05 (missing contacts); registered before the cut-over so the DB exempts it. */
    String legacyCustomer(String name, String doc, boolean active) throws Exception {
        try (var c = db(true); var s = c.prepareStatement("INSERT INTO customers_schema.customer(name,identity_document,active,registration_date) VALUES (?,?,?,'2020-01-01T00:00:00Z') RETURNING customer_id")) {
            s.setString(1, name); s.setString(2, doc); s.setBoolean(3, active); try (var r = s.executeQuery()) { r.next(); return r.getString(1); }
        }
    }
}
