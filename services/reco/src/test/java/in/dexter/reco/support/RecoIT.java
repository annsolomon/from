package in.dexter.reco.support;

import in.dexter.security.jwt.JwtVerifier;
import in.dexter.testkit.containers.ServiceDb;
import in.dexter.testkit.containers.TestPostgres;
import in.dexter.testkit.security.TestJwt;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base of reco's integration tests: the real application on a random port, Flyway (as reco_owner) on a
 * Testcontainers Postgres database laid out like infra, the app pool as reco_app, relay and consumers off (the
 * projection handlers are called directly and events are asserted as outbox rows), the <b>sweeper off</b> so a
 * test decides when a recompute happens, and a test identity provider. One Spring context for all subclasses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RecoIT.TestIdp.class)
public abstract class RecoIT {
  public static final JsonMapper JSON = JsonMapper.builder().build();
  public static final ServiceDb DB = TestPostgres.flywayDb("reco");
  /** Superuser: assertions and bulk seeding only (bypasses RLS). */
  public static final JdbcClient ADMIN = JdbcClient.create(
      new SimpleDriverDataSource(new org.postgresql.Driver(), DB.url(), "postgres", "postgres"));
  public static final TestJwt IDP = TestJwt.create();
  static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @Value("${local.server.port}")
  protected int port;

  @TestConfiguration(proxyBeanMethods = false)
  public static class TestIdp {
    @Bean
    JwtVerifier testJwtVerifier() {
      return IDP.verifier("reco", Clock.systemUTC());
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", DB::url);
    r.add("spring.datasource.password", () -> ServiceDb.PW);
    r.add("spring.flyway.password", () -> ServiceDb.PW);
    r.add("dexter.reco.relay.enabled", () -> "false");
    r.add("dexter.reco.consumers.enabled", () -> "false");
    // the tests drive Sweeper.sweep() themselves: a background pass would race every debounce assertion
    r.add("dexter.reco.sweeper.enabled", () -> "false");
  }

  public static String token(UUID account) {
    return IDP.user(account).audience("reco").sign();
  }

  // ------------------------------------------------------------------------------------------- HTTP

  public record Res(int status, Map<String, String> headers, String body) {
    public JsonNode json() {
      return JSON.readTree(body);
    }

    public String header(String name) {
      return headers.get(name.toLowerCase(Locale.ROOT));
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
      r.headers().map().forEach((k, v) -> h.put(k.toLowerCase(Locale.ROOT), v.getFirst()));
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

  public static String[] auth(UUID account, String... more) {
    String[] h = new String[more.length + 2];
    h[0] = "Authorization";
    h[1] = "Bearer " + token(account);
    System.arraycopy(more, 0, h, 2, more.length);
    return h;
  }

  // ------------------------------------------------------------------------------------------- assertions

  /** The payloads of one event type for one account, oldest first — parsed, because the column is jsonb. */
  public static List<JsonNode> outboxData(UUID account, String eventType) {
    return ADMIN.sql("SELECT payload::text FROM reco.outbox WHERE message_key = :k AND event_type = :t"
            + " ORDER BY created_at, id")
        .param("k", account.toString()).param("t", eventType).query(String.class).list()
        .stream().map(p -> JSON.readTree(p).get("data")).toList();
  }

  public static long outboxRows(UUID account, String eventType) {
    return ADMIN.sql("SELECT count(1) FROM reco.outbox WHERE message_key = :k AND event_type = :t")
        .param("k", account.toString()).param("t", eventType).query(Long.class).single();
  }

  /** One window of the stored spend profile, as (root -> monthly average). */
  public static Map<String, Long> profile(UUID account, String window) {
    Map<String, Long> out = new LinkedHashMap<>();
    ADMIN.sql("SELECT category_root::text AS root, monthly_avg_paise FROM reco.spend_profile"
            + " WHERE account_id = :a AND window_code = :w ORDER BY category_root")
        .param("a", account).param("w", window)
        .query((rs, n) -> Map.entry(rs.getString("root"), rs.getLong("monthly_avg_paise"))).list()
        .forEach(e -> out.put(e.getKey(), e.getValue()));
    return out;
  }

  /** The exact month rows, for comparing the projection against a recomputed sum. */
  public static long monthTotal(UUID account, String root, String month) {
    return ADMIN.sql("SELECT coalesce(sum(total_paise), 0) FROM reco.spend_month WHERE account_id = :a"
            + " AND category_root = :r AND month = CAST(:m AS date)")
        .param("a", account).param("r", root).param("m", month).query(Long.class).single();
  }

  public static Map<String, Object> state(UUID account) {
    List<Map<String, Object>> rows = ADMIN.sql("SELECT dirty, dirty_since, computed_at, attempts, ledger_ver,"
            + " portfolio_ver FROM reco.rec_state WHERE account_id = :a").param("a", account).query().listOfRows();
    return rows.isEmpty() ? Map.of() : rows.get(0);
  }

  public static String storedHash(UUID account) {
    return ADMIN.sql("SELECT state_hash FROM reco.recommendations WHERE account_id = :a")
        .param("a", account).query(String.class).optional().orElse(null);
  }

  public static long historyRows(UUID account) {
    return ADMIN.sql("SELECT count(1) FROM reco.recommendation_history WHERE account_id = :a")
        .param("a", account).query(Long.class).single();
  }
}
