package in.dexter.commerce.support;

import in.dexter.commerce.app.CounterFlusher;
import in.dexter.commerce.app.ProjectionService;
import in.dexter.commerce.domain.PostbackSignature;
import in.dexter.contracts.events.billing.EntitlementsChangedV1;
import in.dexter.contracts.events.catalog.CardPublishedV1;
import in.dexter.contracts.events.portfolio.CardChangedV1;
import in.dexter.core.id.Ids;
import in.dexter.events.EventEnvelope;
import in.dexter.security.jwt.JwtVerifier;
import in.dexter.testkit.containers.ServiceDb;
import in.dexter.testkit.containers.TestPostgres;
import in.dexter.testkit.containers.TestRedis;
import in.dexter.testkit.security.TestJwt;
import java.net.URI;
import java.net.URLEncoder;
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
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
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
 * Base of commerce's integration tests: the real application on a random port, Flyway (as commerce_owner) on a
 * Testcontainers Postgres laid out like infra, the app pool as commerce_app and the admin pool as commerce_admin,
 * Redis as the ACL user {@code commerce} (counters, uniques, frequency caps), relay, consumers and the flusher off
 * (events are asserted as outbox rows; projections are fed through {@link ProjectionService} exactly as the
 * consumers would; tests flush the counters themselves), and a test identity provider. One Spring context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CommerceIT.TestIdp.class)
public abstract class CommerceIT {
  public static final JsonMapper JSON = JsonMapper.builder().build();
  public static final ServiceDb DB = TestPostgres.flywayDb("commerce");
  /** Superuser: assertions and bulk seeding only (bypasses RLS). */
  public static final JdbcClient ADMIN = JdbcClient.create(
      new SimpleDriverDataSource(new org.postgresql.Driver(), DB.url(), "postgres", "postgres"));
  public static final TestJwt IDP = TestJwt.create();
  public static final String POSTBACK_SECRET = "commerce-it-postbacks-000000000000";
  public static final String CLICK_KEY;
  static final String REDIS_PW = "commerce-it-redis-pw";
  static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
      .followRedirects(HttpClient.Redirect.NEVER).build();
  static final String CURSOR_KEY;
  static final AtomicLong VERSIONS = new AtomicLong(1);

  /** Two published card products every test may use (seeded through the catalog consumer). */
  public static final UUID CARD_A = UUID.fromString("0192f1e2-0000-7000-8000-0000000ca001");
  public static final UUID CARD_B = UUID.fromString("0192f1e2-0000-7000-8000-0000000ca002");
  public static final UUID RETIRED_CARD = UUID.fromString("0192f1e2-0000-7000-8000-0000000ca003");

  static {
    SecureRandom r = new SecureRandom();
    byte[] b = new byte[32];
    r.nextBytes(b);
    CURSOR_KEY = Base64.getEncoder().encodeToString(b);
    r.nextBytes(b);
    CLICK_KEY = Base64.getEncoder().encodeToString(b);
  }

  @Value("${local.server.port}")
  protected int port;
  @Autowired
  protected ProjectionService projections;
  @Autowired
  protected CounterFlusher flusher;

  @TestConfiguration(proxyBeanMethods = false)
  public static class TestIdp {
    @Bean
    JwtVerifier testJwtVerifier() {
      return IDP.verifier("commerce", Clock.systemUTC());
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", DB::url);
    r.add("spring.datasource.password", () -> ServiceDb.PW);
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "30");
    r.add("spring.flyway.password", () -> ServiceDb.PW);
    r.add("dexter.cursor.keys.c1", () -> CURSOR_KEY);
    r.add("dexter.commerce.admin.db-url", DB::url);
    r.add("dexter.commerce.admin.db-password", () -> ServiceDb.PW);
    r.add("dexter.commerce.relay.enabled", () -> "false");
    r.add("dexter.commerce.consumers.enabled", () -> "false");
    r.add("dexter.commerce.jobs.enabled", () -> "false");
    r.add("dexter.commerce.clicks.token-key", () -> CLICK_KEY);
    r.add("dexter.commerce.clicks.hash-salt", () -> "commerce-it-salt-0000");
    r.add("dexter.commerce.affiliate-secrets.mock", () -> POSTBACK_SECRET);
    TestRedis.applyServiceAcl("commerce", REDIS_PW);
    r.add("spring.data.redis.host", TestRedis::host);
    r.add("spring.data.redis.port", TestRedis::port);
    r.add("spring.data.redis.username", () -> "commerce");
    r.add("spring.data.redis.password", () -> REDIS_PW);
    r.add("spring.data.redis.timeout", () -> "2s");
  }

  // ------------------------------------------------------------------------------------------- tokens

  public static String token(UUID account) {
    return IDP.user(account).audience("commerce").sign();
  }

  /** An admin-console token with operator roles and the given permissions. */
  public static String operator(UUID admin, List<String> roles, String... perms) {
    return IDP.admin(admin, roles.toArray(String[]::new)).scope("admin").audience("commerce").permissions(perms)
        .sign();
  }

  /** The standing admin of the seed: SUPPORT + CATALOG_EDITOR with every commerce permission. */
  public static String operator() {
    return operator(UUID.fromString("0192f1e2-0000-7000-8000-0000000ad001"), List.of("SUPPORT", "CATALOG_EDITOR"),
        "commerce:read", "commerce:write", "commerce:decide");
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
    return sendBytes(method, path, body == null ? null : body.getBytes(StandardCharsets.UTF_8), headers);
  }

  public Res sendBytes(String method, String path, byte[] body, String... headers) {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .timeout(Duration.ofSeconds(30))
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(body));
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

  public Res get(String path, UUID account) {
    return account == null ? send("GET", path, null) : send("GET", path, null, auth(account));
  }

  public Res admin(String method, String path, String body, String... more) {
    return send(method, path, body, authWith(operator(), more));
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

  public static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  // ------------------------------------------------------------------------------------------- data

  /** Publishes the shared card products through the catalog consumer (idempotent: newer versions only). */
  protected void ensureCards() {
    publishCard(CARD_A, "mock-it-card-a", "IT Card A", "published");
    publishCard(CARD_B, "mock-it-card-b", "IT Card B", "published");
    publishCard(RETIRED_CARD, "mock-it-card-retired", "IT Card Retired", "retired");
  }

  protected void publishCard(UUID card, String slug, String name, String status) {
    CardPublishedV1 data = new CardPublishedV1(card, slug, status, 1, "2026-01-01", null,
        Map.of("name", name, "issuerCode", "MOCK", "network", "visa"));
    projections.onCatalogEvent(envelope(status.equals("retired") ? CardPublishedV1.RETIRED : CardPublishedV1.PUBLISHED,
        null, card, VERSIONS.incrementAndGet(), data));
  }

  /** The account holds {@code card} (portfolio.card.added through the held-cards consumer). */
  protected void holdCard(UUID account, UUID card) {
    UUID userCard = Ids.newId();
    projections.onCardEvent(envelope(CardChangedV1.ADDED, account, userCard, 1,
        new CardChangedV1(userCard, card, "mine", "4242", "visa", 5, "active", 1, Instant.now().toString())));
  }

  /** billing.entitlements.changed for the account (through the ad-free consumer). */
  protected void entitlements(UUID account, String tier, boolean adFree, long version) {
    projections.onEntitlementsEvent(envelope(EntitlementsChangedV1.TYPE, account, account, version,
        new EntitlementsChangedV1(account, tier, new TreeMap<>(Map.of("adFree", adFree)), version,
            Instant.now().toString())));
  }

  protected static EventEnvelope envelope(String type, UUID account, UUID aggregate, long version, Object data) {
    return new EventEnvelope(Ids.newId(), type, 1, "it", Instant.now(), account, aggregate, version, null,
        JSON.valueToTree(data));
  }

  /** A deal body for the admin API. */
  public static String dealBody(String title, String category, List<UUID> eligible, long referenceSpendPaise,
      boolean sponsored, int sponsorWeight, String status) {
    StringBuilder e = new StringBuilder("[");
    for (int i = 0; i < eligible.size(); i++) {
      e.append(i == 0 ? "" : ",").append('"').append(eligible.get(i)).append('"');
    }
    e.append(']');
    return "{\"merchantId\":\"" + UUID.randomUUID() + "\",\"merchantName\":\"Mock Mart\",\"title\":\"" + title
        + "\",\"categoryPath\":\"" + category + "\",\"components\":[{\"kind\":\"percent\",\"valueBps\":1000,"
        + "\"capPaise\":500000},{\"kind\":\"percent\",\"valueBps\":500,\"capPaise\":200000}],"
        + "\"eligibleCardProducts\":" + e + ",\"referenceSpendPaise\":" + referenceSpendPaise + ",\"sponsored\":"
        + sponsored + ",\"sponsorWeight\":" + sponsorWeight + ",\"status\":\"" + status
        + "\",\"network\":\"mock\",\"landingUrl\":\"https://merchant.example/p?ref=dexter\",\"commissionBps\":400}";
  }

  /** Creates a deal through the admin API; returns its id. */
  public UUID deal(String title, String category, List<UUID> eligible, long referenceSpendPaise) {
    Res r = admin("POST", "/v1/admin/commerce/deals",
        dealBody(title, category, eligible, referenceSpendPaise, false, 0, "active"));
    if (r.status() != 201) {
      throw new AssertionError("create deal: " + r);
    }
    return UUID.fromString(r.json().get("id").asString());
  }

  /** The signed click path of a deal as the feed would hand it to {@code viewer} (null = anonymous). */
  public String clickPath(UUID deal, UUID viewer) {
    Res r = get("/v1/deals/" + deal, viewer);
    if (r.status() != 200) {
      throw new AssertionError("deal: " + r);
    }
    return r.json().get("deal").get("clickPath").asString();
  }

  public static String postbackSignature(byte[] body, Instant at) {
    return PostbackSignature.header(POSTBACK_SECRET.getBytes(StandardCharsets.UTF_8), at.getEpochSecond(), body);
  }

  public Res postback(String json) {
    byte[] b = json.getBytes(StandardCharsets.UTF_8);
    return sendBytes("POST", "/v1/webhooks/affiliate/mock", b, "Postback-Signature",
        postbackSignature(b, Instant.now()));
  }

  // ------------------------------------------------------------------------------------------- assertions

  public static long count(String sql, Object... params) {
    var q = ADMIN.sql(sql);
    for (int i = 0; i < params.length; i++) {
      q = q.param(i + 1, params[i]);
    }
    return q.query(Long.class).single();
  }

  public static List<JsonNode> outbox(UUID account, String eventType) {
    return ADMIN.sql("SELECT payload::text FROM commerce.outbox WHERE message_key = :k AND event_type = :t"
            + " ORDER BY created_at, id")
        .param("k", account.toString()).param("t", eventType).query(String.class).list().stream()
        .map(p -> JSON.readTree(p).get("data")).toList();
  }

  public static long outboxRows(UUID account, String eventType) {
    return count("SELECT count(1) FROM commerce.outbox WHERE message_key = ? AND event_type = ?", account.toString(),
        eventType);
  }
}
