package in.dexter.billing.support;

import in.dexter.billing.app.CheckoutService;
import in.dexter.billing.domain.WebhookSignature;
import in.dexter.billing.infra.provider.MemoryWebhookSink;
import in.dexter.billing.infra.provider.MockPaymentProvider;
import in.dexter.security.jwt.JwtVerifier;
import in.dexter.testkit.containers.ServiceDb;
import in.dexter.testkit.containers.TestPostgres;
import in.dexter.testkit.containers.TestRedis;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base of billing's integration tests: the real application on a random port, Flyway (as billing_owner) on a
 * Testcontainers Postgres laid out like infra, the app pool as billing_app, Redis as the ACL user {@code billing}
 * (the entitlements cache), relay and jobs off (events are asserted as outbox rows; the sweeper and the period-end
 * job are called directly), a test identity provider, and the mock provider's webhooks kept in memory so each test
 * decides when, how often and in what order they are delivered. One Spring context for all subclasses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BillingIT.TestIdp.class)
public abstract class BillingIT {
  public static final JsonMapper JSON = JsonMapper.builder().build();
  public static final ServiceDb DB = TestPostgres.flywayDb("billing");
  /** Superuser: assertions and bulk seeding only (bypasses RLS). */
  public static final JdbcClient ADMIN = JdbcClient.create(
      new SimpleDriverDataSource(new org.postgresql.Driver(), DB.url(), "postgres", "postgres"));
  public static final TestJwt IDP = TestJwt.create();
  public static final String WEBHOOK_SECRET = "billing-it-webhooks-0000000000000000";
  static final String REDIS_PW = "billing-it-redis-pw";
  static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  static final String CURSOR_KEY;

  static {
    byte[] b = new byte[32];
    new SecureRandom().nextBytes(b);
    CURSOR_KEY = Base64.getEncoder().encodeToString(b);
  }

  @Value("${local.server.port}")
  protected int port;
  @Autowired
  protected MockPaymentProvider provider;
  @Autowired
  protected MockPaymentProvider.Sink sinkBean;
  @Autowired
  protected CheckoutService checkoutService;

  @TestConfiguration(proxyBeanMethods = false)
  public static class TestIdp {
    @Bean
    JwtVerifier testJwtVerifier() {
      return IDP.verifier("billing", Clock.systemUTC());
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", DB::url);
    r.add("spring.datasource.password", () -> ServiceDb.PW);
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "30");
    r.add("spring.flyway.password", () -> ServiceDb.PW);
    r.add("dexter.cursor.keys.c1", () -> CURSOR_KEY);
    r.add("dexter.billing.relay.enabled", () -> "false");
    r.add("dexter.billing.jobs.enabled", () -> "false");
    r.add("dexter.billing.mock-provider.webhook-secret", () -> WEBHOOK_SECRET);
    r.add("dexter.billing.mock-provider.delivery", () -> "memory");
    r.add("dexter.billing.mock-provider.webhook-delay", () -> "20ms");
    r.add("dexter.billing.mock-provider.delayed-outcome-after", () -> "200ms");
    // Redis as the restricted service user `billing` (infra/redis/acl.tmpl)
    TestRedis.applyServiceAcl("billing", REDIS_PW);
    r.add("spring.data.redis.host", TestRedis::host);
    r.add("spring.data.redis.port", TestRedis::port);
    r.add("spring.data.redis.username", () -> "billing");
    r.add("spring.data.redis.password", () -> REDIS_PW);
    r.add("spring.data.redis.timeout", () -> "2s");
  }

  protected MemoryWebhookSink sink() {
    return (MemoryWebhookSink) sinkBean;
  }

  public static String token(UUID account) {
    return IDP.user(account).audience("billing").sign();
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

  public Res send(String method, String path, String body, String contentType, String... headers) {
    return sendBytes(method, path, body == null ? null : body.getBytes(StandardCharsets.UTF_8), contentType, headers);
  }

  public Res sendBytes(String method, String path, byte[] body, String contentType, String... headers) {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .timeout(Duration.ofSeconds(30))
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(body));
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
    return authWith(token(account), more);
  }

  public static String[] authWith(String token, String... more) {
    String[] h = new String[more.length + 2];
    h[0] = "Authorization";
    h[1] = "Bearer " + token;
    System.arraycopy(more, 0, h, 2, more.length);
    return h;
  }

  // ------------------------------------------------------------------------------------------- checkout

  public static String checkoutBody(String plan, String paymentMethod, String state) {
    return "{\"plan\":\"" + plan + "\",\"paymentMethod\":\"" + paymentMethod + "\",\"billingState\":\"" + state + "\"}";
  }

  public Res checkout(UUID account, UUID key, String plan, String paymentMethod, String state) {
    return send("POST", "/v1/subscriptions/checkout", checkoutBody(plan, paymentMethod, state), "application/json",
        auth(account, "Idempotency-Key", key.toString()));
  }

  /** A successful PREMIUM checkout in Karnataka (the supplier's state): the subscription is active on return. */
  public Res subscribe(UUID account) {
    Res r = checkout(account, UUID.randomUUID(), "PREMIUM", "pm_mock_success", "KA");
    if (r.status() != 201) {
      throw new AssertionError("checkout: " + r);
    }
    return r;
  }

  public Res cancel(UUID account) {
    return send("POST", "/v1/subscriptions/me:cancel", null, null, auth(account));
  }

  // ------------------------------------------------------------------------------------------- webhooks

  /** Sends one captured delivery to the webhook endpoint exactly as the provider would. */
  public Res deliver(MemoryWebhookSink.Delivery d) {
    return sendBytes("POST", "/v1/webhooks/mock", d.body(), "application/json", "Webhook-Signature", d.signature());
  }

  /** The provider's delivery of {@code type} for the payment, waiting for the provider's timer to produce it. */
  public MemoryWebhookSink.Delivery delivery(String type, UUID paymentId) {
    return sink().await(d -> d.is(type, paymentId.toString()), Duration.ofSeconds(10));
  }

  /** Signs an arbitrary body with the test secret (a forged or crafted delivery). */
  public static String sign(byte[] body, Instant at) {
    return WebhookSignature.header(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), at.getEpochSecond(), body);
  }

  // ------------------------------------------------------------------------------------------- assertions

  public static UUID paymentOf(Res checkout) {
    return UUID.fromString(checkout.json().get("payment").get("id").asString());
  }

  public static long count(String sql, Object... params) {
    var q = ADMIN.sql(sql);
    for (int i = 0; i < params.length; i++) {
      q = q.param(i + 1, params[i]);
    }
    return q.query(Long.class).single();
  }

  public static String one(String sql, Object... params) {
    var q = ADMIN.sql(sql);
    for (int i = 0; i < params.length; i++) {
      q = q.param(i + 1, params[i]);
    }
    return q.query(String.class).single();
  }

  /** The {@code data} of one event type for one account, oldest first (parsed: jsonb renders ": " with a space). */
  public static List<JsonNode> outbox(UUID account, String eventType) {
    return ADMIN.sql("SELECT payload::text FROM billing.outbox WHERE message_key = :k AND event_type = :t"
            + " ORDER BY created_at, id")
        .param("k", account.toString()).param("t", eventType).query(String.class).list().stream()
        .map(p -> JSON.readTree(p).get("data")).toList();
  }

  public static long outboxRows(UUID account, String eventType) {
    return count("SELECT count(1) FROM billing.outbox WHERE message_key = ? AND event_type = ?", account.toString(),
        eventType);
  }

  /** Makes a Mode B key's lease run out now (what waiting 30 s would do). */
  public static void expireLease(UUID account, UUID key) {
    ADMIN.sql("UPDATE billing.idempotency_keys SET locked_until = now() - interval '1 second'"
        + " WHERE account_id = :a AND idem_key = :k").param("a", account).param("k", key).update();
  }
}
