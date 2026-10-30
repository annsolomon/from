package in.dexter.planning.support;

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
import java.util.List;
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
 * Base of planning's integration tests: the real application on a random port, Flyway (as planning_owner) on a
 * Testcontainers Postgres database laid out like infra, the app pool as planning_app, relay and consumers off
 * (events are asserted as outbox rows and the consumer handler is called directly), and a test identity provider.
 * One Spring context for all subclasses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PlanningIT.TestIdp.class)
public abstract class PlanningIT {
  public static final JsonMapper JSON = JsonMapper.builder().build();
  public static final ServiceDb DB = TestPostgres.flywayDb("planning");
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
      return IDP.verifier("planning", Clock.systemUTC());
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", DB::url);
    r.add("spring.datasource.password", () -> ServiceDb.PW);
    r.add("spring.flyway.password", () -> ServiceDb.PW);
    r.add("dexter.cursor.keys.c1", () -> CURSOR_KEY);
    r.add("dexter.planning.relay.enabled", () -> "false");
    r.add("dexter.planning.consumers.enabled", () -> "false");
  }

  public static String token(UUID account) {
    return IDP.user(account).audience("planning").sign();
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
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
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

  public Res get(String path, UUID account) {
    return send("GET", path, null, null, auth(account));
  }

  /** POST with a fresh Idempotency-Key. */
  public Res post(String path, UUID account, Object body) {
    return send("POST", path, body == null ? null : JSON.writeValueAsString(body), "application/json",
        auth(account, "Idempotency-Key", UUID.randomUUID().toString()));
  }

  /** POST with a caller-chosen key (replay tests). */
  public Res post(String path, UUID account, Object body, String key) {
    return send("POST", path, body == null ? null : JSON.writeValueAsString(body), "application/json",
        auth(account, "Idempotency-Key", key));
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

  // ------------------------------------------------------------------------------------------- bodies

  public static Map<String, Object> budget(String name, String period, String categoryPath, long limitPaise,
      String startsOn) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", name);
    m.put("period", period);
    m.put("categoryPath", categoryPath);
    m.put("limitPaise", limitPaise);
    m.put("startsOn", startsOn);
    return m;
  }

  public static Map<String, Object> liability(String kind, String lender, long principalPaise, int aprBps,
      int tenureMonths, String startDate) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("kind", kind);
    m.put("lender", lender);
    m.put("principalPaise", principalPaise);
    m.put("aprBps", aprBps);
    m.put("tenureMonths", tenureMonths);
    m.put("startDate", startDate);
    return m;
  }

  /** A new budget for the account; returns its id. */
  public UUID newBudget(UUID account, Map<String, Object> body) {
    Res r = post("/v1/budgets", account, body);
    if (r.status() != 201) {
      throw new AssertionError(r.toString());
    }
    return UUID.fromString(r.json().get("id").asString());
  }

  public UUID newLiability(UUID account, Map<String, Object> body) {
    Res r = post("/v1/liabilities", account, body);
    if (r.status() != 201) {
      throw new AssertionError(r.toString());
    }
    return UUID.fromString(r.json().get("id").asString());
  }

  /** The spend recorded for one budget period, straight from the table. */
  public static long spend(UUID budgetId, String periodStart) {
    return ADMIN.sql("SELECT coalesce(max(spent_paise), 0) FROM planning.budget_period_spend"
            + " WHERE budget_id = :b AND period_start = CAST(:p AS date)")
        .param("b", budgetId).param("p", periodStart).query(Long.class).single();
  }

  /** The thresholds alerted for one budget period, in order. */
  public static List<Integer> alerted(UUID budgetId, String periodStart) {
    return ADMIN.sql("SELECT threshold FROM planning.budget_alerts_sent"
            + " WHERE budget_id = :b AND period_start = CAST(:p AS date) ORDER BY threshold")
        .param("b", budgetId).param("p", periodStart).query(Integer.class).list();
  }

  /**
   * The payloads of one event type for one account, oldest first. Scoped to the account because every IT class
   * shares this context and its database: a global count would see other tests' rows.
   */
  public static List<String> outbox(UUID account, String eventType) {
    return ADMIN.sql("SELECT payload::text FROM planning.outbox"
            + " WHERE message_key = :k AND event_type = :t ORDER BY created_at, id")
        .param("k", account.toString()).param("t", eventType).query(String.class).list();
  }

  /**
   * The {@code data} of one event type for one account, oldest first — parsed, because the stored payload is
   * {@code jsonb} and Postgres renders it with a space after each colon, which a substring assertion would miss.
   */
  public static List<JsonNode> outboxData(UUID account, String eventType) {
    return outbox(account, eventType).stream().map(p -> JSON.readTree(p).get("data")).toList();
  }

  public static long outboxRows(UUID account, String eventType) {
    return ADMIN.sql("SELECT count(1) FROM planning.outbox WHERE message_key = :k AND event_type = :t")
        .param("k", account.toString()).param("t", eventType).query(Long.class).single();
  }
}
