package in.dexter.ledger.support;

import in.dexter.security.jwt.JwtVerifier;
import in.dexter.testkit.containers.ServiceDb;
import in.dexter.testkit.containers.TestPostgres;
import in.dexter.testkit.security.TestJwt;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base of ledger's integration tests: the real application on a random port, Flyway (as ledger_owner) on a
 * Testcontainers Postgres database laid out like infra, the app pool as ledger_app, the relay off (events are
 * asserted as outbox rows), and a test identity provider ({@link TestJwt}) whose verifier replaces the JWKS one.
 * One Spring context for all subclasses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(LedgerIT.TestIdp.class)
public abstract class LedgerIT {
  public static final JsonMapper JSON = JsonMapper.builder().build();
  public static final ServiceDb DB = TestPostgres.flywayDb("ledger");
  /** Superuser: assertions and bulk seeding only (bypasses RLS). */
  public static final JdbcClient ADMIN = JdbcClient.create(
      new SimpleDriverDataSource(new org.postgresql.Driver(), DB.url(), "postgres", "postgres"));
  public static final TestJwt IDP = TestJwt.create();
  static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  static final String CURSOR_KEY;

  static {
    byte[] b = new byte[32];
    new SecureRandom().nextBytes(b);
    CURSOR_KEY = Base64.getEncoder().encodeToString(b);
  }

  @Value("${local.server.port}")
  protected int port;

  @TestConfiguration(proxyBeanMethods = false)
  public static class TestIdp {
    @Bean
    JwtVerifier testJwtVerifier() {
      return IDP.verifier("ledger", Clock.systemUTC());
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", DB::url);
    r.add("spring.datasource.password", () -> ServiceDb.PW);
    r.add("spring.flyway.password", () -> ServiceDb.PW);
    r.add("dexter.cursor.keys.c1", () -> CURSOR_KEY);
    r.add("dexter.ledger.relay.enabled", () -> "false");
  }

  public static String token(UUID account) {
    return IDP.user(account).audience("ledger").sign();
  }

  // ------------------------------------------------------------------------------------------- HTTP

  public record Res(int status, Map<String, String> headers, String body) {
    public JsonNode json() {
      return JSON.readTree(body);
    }

    public String header(String name) {
      return headers.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    public String code() {
      JsonNode c = json().get("code");
      return c == null ? null : c.asString();
    }

    @Override
    public String toString() {
      return status + " " + body;
    }
  }

  public Res send(String method, String path, String body, String contentType, String... headers) {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .timeout(Duration.ofSeconds(30))
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    if (body != null) {
      b.header("Content-Type", contentType);
    }
    for (int i = 0; i + 1 < headers.length; i += 2) {
      b.header(headers[i], headers[i + 1]);
    }
    try {
      HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
      Map<String, String> h = new LinkedHashMap<>();
      r.headers().map().forEach((k, v) -> h.put(k.toLowerCase(java.util.Locale.ROOT), v.getFirst()));
      return new Res(r.statusCode(), h, r.body());
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  public Res get(String path, UUID account, String... more) {
    return send("GET", path, null, null, auth(account, more));
  }

  public Res post(String path, UUID account, Object body, String... more) {
    return send("POST", path, body == null ? null : JSON.writeValueAsString(body), "application/json",
        auth(account, more));
  }

  public Res patch(String path, UUID account, String json, String ifMatch) {
    return ifMatch == null ? send("PATCH", path, json, "application/merge-patch+json", auth(account))
        : send("PATCH", path, json, "application/merge-patch+json", auth(account, "If-Match", ifMatch));
  }

  public Res delete(String path, UUID account) {
    return send("DELETE", path, null, null, auth(account));
  }

  public static String[] auth(UUID account, String... more) {
    String[] h = new String[more.length + 2];
    h[0] = "Authorization";
    h[1] = "Bearer " + token(account);
    System.arraycopy(more, 0, h, 2, more.length);
    return h;
  }

  /** A valid create body. */
  public static Map<String, Object> expense(String occurredAt, long paise, String category, String method) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("occurredAt", occurredAt);
    m.put("amountPaise", paise);
    m.put("merchant", "Test Merchant");
    m.put("categoryPath", category);
    m.put("paymentMethod", method);
    return m;
  }

  public Res create(UUID account, Map<String, Object> body) {
    return post("/v1/expenses", account, body, "Idempotency-Key", UUID.randomUUID().toString());
  }

  public static long outboxRows(UUID account, String type) {
    return ADMIN.sql("SELECT count(1) FROM ledger.outbox WHERE message_key = :k AND event_type = :t")
        .param("k", account.toString()).param("t", type).query(Long.class).single();
  }
}
