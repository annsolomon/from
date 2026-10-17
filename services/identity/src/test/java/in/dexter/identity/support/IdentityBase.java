package in.dexter.identity.support;

import in.dexter.identity.infra.MemoryTransport;
import in.dexter.testkit.containers.ServiceDb;
import in.dexter.testkit.containers.TestPostgres;
import in.dexter.testkit.containers.TestRedis;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base of identity's integration tests: the real application on a random port, Flyway (as identity_owner) on a
 * Testcontainers Postgres 17 database laid out like infra (TestPostgres.flywayDb), the app pool as identity_app,
 * mail captured in memory, the outbox relay off (events are asserted as outbox rows). One Spring context for all
 * subclasses (same configuration → context cache). Tests extend {@link IdentityIT} (shared context, relaxed
 * throttles); LockoutIT extends this class directly with production-like throttles.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IdentityBase {
  public static final String ISSUER = "https://identity.dexter.test";
  public static final JsonMapper JSON = JsonMapper.builder().build();
  static final ServiceDb DB = TestPostgres.flywayDb("identity");
  static final SecureRandom RANDOM = new SecureRandom();
  public static final String REDIS_PW = "identity-it-pw";
  /**
   * One set of keys per JVM: every test context (IdentityIT's and LockoutIT's) shares the database, so the signing
   * keys and DEKs one context wrapped must open in the other.
   */
  static final String KEK = randomKey();
  static final String PEPPER = randomKey();
  static final String CURSOR_KEY = randomKey();
  /** Superuser: assertions and bulk seeding only (bypasses RLS). */
  public static final JdbcClient ADMIN = JdbcClient.create(
      new SimpleDriverDataSource(new org.postgresql.Driver(), DB.url(), "postgres", "postgres"));
  static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  static final Pattern LINK_TOKEN = Pattern.compile("#(?:verify|reset)=([A-Za-z0-9_-]{43})");

  @Value("${local.server.port}")
  protected int port;
  @Autowired
  protected MemoryTransport mail;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", DB::url);
    r.add("spring.datasource.password", () -> ServiceDb.PW);
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "30");
    r.add("spring.flyway.password", () -> ServiceDb.PW);
    r.add("dexter.cursor.keys.c1", () -> CURSOR_KEY);
    r.add("dexter.identity.issuer", () -> ISSUER);
    r.add("dexter.identity.kek.keys.k1", () -> KEK);
    r.add("dexter.identity.phone-pepper", () -> PEPPER);
    r.add("dexter.identity.mail.mode", () -> "memory");
    r.add("dexter.identity.relay.enabled", () -> "false");
    r.add("dexter.identity.service-clients.ledger.secret", () -> "ledger-test-secret");
    r.add("dexter.identity.service-clients.catalog.secret", () -> "");
    // P05: Redis as the restricted service user (infra/redis/acl.tmpl), the identity_admin pool on the same DB
    TestRedis.applyServiceAcl("identity", REDIS_PW);
    r.add("spring.data.redis.host", TestRedis::host);
    r.add("spring.data.redis.port", TestRedis::port);
    r.add("spring.data.redis.username", () -> "identity");
    r.add("spring.data.redis.password", () -> REDIS_PW);
    r.add("spring.data.redis.timeout", () -> "2s");
    r.add("dexter.identity.admin.db-url", DB::url);
    r.add("dexter.identity.admin.db-password", () -> ServiceDb.PW);
  }

  static String randomKey() {
    byte[] b = new byte[32];
    RANDOM.nextBytes(b);
    return Base64.getEncoder().encodeToString(b);
  }

  // ------------------------------------------------------------------------------------------- HTTP

  /** Status, headers (first value, lower-case names) and body. */
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

  protected URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  protected Res send(String method, String path, String body, String contentType, String... headers) {
    HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30))
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

  protected Res post(String path, Object body, String... headers) {
    return send("POST", path, body == null ? null : JSON.writeValueAsString(body), "application/json", headers);
  }

  protected Res get(String path, String... headers) {
    return send("GET", path, null, null, headers);
  }

  protected Res delete(String path, String... headers) {
    return send("DELETE", path, null, null, headers);
  }

  /** The (unverified) payload of a JWT, for asserting claims the server put there. */
  public static JsonNode claims(String jwt) {
    String[] parts = jwt.split("\\.");
    return JSON.readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
  }

  /** The TOTP code for {@code base32Secret} at {@code stepOffset} steps from now. */
  public static String totp(String base32Secret, int stepOffset) {
    byte[] secret = in.dexter.identity.domain.Totp.Base32.decode(base32Secret);
    long step = in.dexter.identity.domain.Totp.step(java.time.Instant.now()) + stepOffset;
    return in.dexter.identity.domain.Totp.code(secret, step, 6);
  }

  protected static String[] bearer(String token, String... more) {
    String[] h = new String[more.length + 2];
    h[0] = "Authorization";
    h[1] = "Bearer " + token;
    System.arraycopy(more, 0, h, 2, more.length);
    return h;
  }

  protected static String form(Map<String, String> fields) {
    StringBuilder sb = new StringBuilder();
    fields.forEach((k, v) -> {
      if (!sb.isEmpty()) {
        sb.append('&');
      }
      sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8));
    });
    return sb.toString();
  }

  // ------------------------------------------------------------------------------------------- flows

  public static String uniqueEmail(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 12) + "@it.test";
  }

  public static final String PASSWORD = "Correct-Horse-Battery-9";

  protected Res register(String email, String password) {
    return post("/v1/auth/register", Map.of("email", email, "password", password, "displayName", "IT User"),
        "Idempotency-Key", UUID.randomUUID().toString());
  }

  /** Waits for the after-commit email to {@code to} whose subject contains {@code subject}; returns the link token. */
  protected String awaitMailToken(String to, String subject) {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      List<in.dexter.identity.app.Mailer.Mail> sent = mail.sent();
      for (int i = sent.size() - 1; i >= 0; i--) {
        var m = sent.get(i);
        if (m.to().equals(to) && m.subject().contains(subject)) {
          Matcher x = LINK_TOKEN.matcher(m.text());
          if (x.find()) {
            return x.group(1);
          }
        }
      }
      sleep(25);
    }
    throw new AssertionError("no mail '" + subject + "' to " + to + " within 10s; sent=" + mail.sent().size());
  }

  /** Waits until a mail to {@code to} with {@code subject} exists (no token expected). */
  protected boolean awaitMail(String to, String subject, Duration within) {
    long deadline = System.nanoTime() + within.toNanos();
    while (System.nanoTime() < deadline) {
      if (mail.sent().stream().anyMatch(m -> m.to().equals(to) && m.subject().contains(subject))) {
        return true;
      }
      sleep(25);
    }
    return false;
  }

  /** register → verify → login; returns the login response JSON. */
  protected JsonNode signUpAndLogin(String email) {
    Res reg = register(email, PASSWORD);
    if (reg.status() != 202) {
      throw new AssertionError("register: " + reg);
    }
    String token = awaitMailToken(email, "Verify");
    Res v = post("/v1/auth/verify-email", Map.of("token", token));
    if (v.status() != 200) {
      throw new AssertionError("verify: " + v);
    }
    return login(email, PASSWORD).json();
  }

  protected Res login(String email, String password) {
    return post("/v1/auth/login", Map.of("email", email, "password", password));
  }

  protected static UUID accountIdOf(String email) {
    return ADMIN.sql("SELECT id FROM identity.accounts WHERE email = CAST(:e AS citext)").param("e", email)
        .query(UUID.class).single();
  }

  protected static long outboxRows(UUID accountId, String type) {
    return ADMIN.sql("SELECT count(1) FROM identity.outbox WHERE message_key = :k AND event_type = :t")
        .param("k", accountId.toString()).param("t", type).query(Long.class).single();
  }

  /** {@code identity.security.alert} kinds for an account, oldest first. */
  protected static List<String> alertKinds(UUID accountId) {
    return ADMIN.sql("SELECT payload->'data'->>'kind' FROM identity.outbox WHERE message_key = :k"
            + " AND event_type = 'identity.security.alert' ORDER BY created_at, id")
        .param("k", accountId.toString()).query(String.class).list();
  }

  protected static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
