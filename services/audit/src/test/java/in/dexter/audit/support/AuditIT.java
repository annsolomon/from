package in.dexter.audit.support;

import in.dexter.audit.app.EventIngest;
import in.dexter.audit.app.NotificationService;
import in.dexter.audit.app.PrivacyService;
import in.dexter.core.id.Ids;
import in.dexter.events.EventEnvelope;
import in.dexter.security.jwt.JwtVerifier;
import in.dexter.testkit.containers.ServiceDb;
import in.dexter.testkit.containers.TestPostgres;
import in.dexter.testkit.security.TestJwt;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base of audit's integration tests: the real application on a random port, Flyway (as audit_owner) on a
 * Testcontainers Postgres laid out like infra, the app pool as audit_app and the admin pool as audit_admin; relay,
 * consumers and jobs off (events are fed through {@link EventIngest} and {@link PrivacyService#onAck} exactly as the
 * consumers would; tests run the worker and the saga's job themselves); the object store and the email / push
 * transport in memory. The saga here has two participants, identity and ledger, whose acknowledgements the tests
 * write. One Spring context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AuditIT.TestBeans.class)
public abstract class AuditIT {
  public static final JsonMapper JSON = JsonMapper.builder().build();
  public static final ServiceDb DB = TestPostgres.flywayDb("audit");
  /** Superuser: assertions, seeding and tampering only (bypasses RLS). */
  public static final JdbcClient ADMIN = JdbcClient.create(
      new SimpleDriverDataSource(new org.postgresql.Driver(), DB.url(), "postgres", "postgres"));
  public static final TestJwt IDP = TestJwt.create();
  public static final MemoryExportStore STORE = new MemoryExportStore();
  public static final ScriptedSender SENDER = new ScriptedSender();
  public static final UUID OPERATOR = UUID.fromString("0192f1e2-0000-7000-8000-0000000ad001");
  static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  static final String CURSOR_KEY;
  static final String SUBJECT_KEY;

  static {
    SecureRandom r = new SecureRandom();
    byte[] b = new byte[32];
    r.nextBytes(b);
    CURSOR_KEY = Base64.getEncoder().encodeToString(b);
    r.nextBytes(b);
    SUBJECT_KEY = Base64.getEncoder().encodeToString(b);
  }

  @Value("${local.server.port}")
  protected int port;
  @Autowired
  protected EventIngest ingest;
  @Autowired
  protected NotificationService notifications;
  @Autowired
  protected PrivacyService privacy;

  @TestConfiguration(proxyBeanMethods = false)
  public static class TestBeans {
    @Bean
    JwtVerifier testJwtVerifier() {
      return IDP.verifier("audit", Clock.systemUTC());
    }

    @Bean
    @Primary
    MemoryExportStore memoryExportStore() {
      return STORE;
    }

    @Bean
    @Primary
    ScriptedSender scriptedSender() {
      return SENDER;
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", DB::url);
    r.add("spring.datasource.password", () -> ServiceDb.PW);
    r.add("spring.flyway.password", () -> ServiceDb.PW);
    r.add("dexter.cursor.keys.c1", () -> CURSOR_KEY);
    r.add("dexter.audit.admin.db-url", DB::url);
    r.add("dexter.audit.admin.db-password", () -> ServiceDb.PW);
    r.add("dexter.audit.relay.enabled", () -> "false");
    r.add("dexter.audit.consumers.enabled", () -> "false");
    r.add("dexter.audit.jobs.enabled", () -> "false");
    r.add("dexter.audit.store.endpoint", () -> "http://127.0.0.1:1");
    r.add("dexter.audit.privacy.subject-key", () -> SUBJECT_KEY);
    r.add("dexter.audit.privacy.services", () -> "identity,ledger");
    r.add("dexter.audit.privacy.phase-timeout", () -> "1s");
    r.add("dexter.audit.privacy.max-attempts", () -> "3");
    r.add("dexter.audit.notifications.backoff-base", () -> "200ms");
    r.add("dexter.audit.notifications.backoff-cap", () -> "10s");
    r.add("dexter.audit.notifications.max-attempts", () -> "4");
  }

  // ------------------------------------------------------------------------------------------- tokens

  /** A user token signed in just now (step-up satisfied). */
  public static String token(UUID account) {
    return IDP.user(account).audience("audit").authTime(Instant.now()).sign();
  }

  public static String operator(String... perms) {
    return IDP.admin(OPERATOR, "SUPPORT").scope("admin").audience("audit").permissions(perms).sign();
  }

  public static String superAdmin() {
    return IDP.admin(OPERATOR, "SUPER_ADMIN").scope("admin").audience("audit")
        .permissions("audit:read", "audit:verify").sign();
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
      return c == null || c.isNull() ? null : c.asString();
    }

    @Override
    public String toString() {
      return status + " " + body;
    }
  }

  public Res send(String method, String path, String body, String... headers) {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .timeout(Duration.ofSeconds(30))
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    if (body != null) {
      b.header("Content-Type", "application/json");
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

  public Res user(String method, String path, UUID account, String body, String... more) {
    return send(method, path, body, authWith(token(account), more));
  }

  public Res admin(String method, String path, String token) {
    return send(method, path, null, "Authorization", "Bearer " + token);
  }

  public static String[] authWith(String token, String... more) {
    String[] h = new String[more.length + 2];
    h[0] = "Authorization";
    h[1] = "Bearer " + token;
    System.arraycopy(more, 0, h, 2, more.length);
    return h;
  }

  // ------------------------------------------------------------------------------------------- events

  public static EventEnvelope envelope(String type, int version, String source, UUID account, Object data) {
    return new EventEnvelope(Ids.newId(), type, version, source, Instant.now(), account, Ids.newId(), 1, null,
        JSON.valueToTree(data));
  }

  public static long count(String sql, Object... params) {
    var q = ADMIN.sql(sql);
    for (int i = 0; i < params.length; i++) {
      q = q.param(i + 1, params[i]);
    }
    return q.query(Long.class).single();
  }

  public static List<Map<String, Object>> rows(String sql, Object... params) {
    var q = ADMIN.sql(sql);
    for (int i = 0; i < params.length; i++) {
      q = q.param(i + 1, params[i]);
    }
    return q.query().listOfRows();
  }
}
