package in.dexter.portfolio.support;

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
 * Base of portfolio's integration tests: the real application on a random port, Flyway (as portfolio_owner) on a
 * Testcontainers Postgres database laid out like infra, the app pool as portfolio_app, relay and consumers off
 * (events are asserted as outbox rows; consumer handlers are called directly, one IT goes through Kafka), the
 * statement closer off, and a test identity provider. Two catalog products are in the projection (as the seed
 * writes them): a points card and a cashback card. One Spring context for all subclasses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PortfolioIT.TestIdp.class)
public abstract class PortfolioIT {
  public static final JsonMapper JSON = JsonMapper.builder().build();
  public static final ServiceDb DB = TestPostgres.flywayDb("portfolio");
  /** Superuser: assertions and bulk seeding only (bypasses RLS). */
  public static final JdbcClient ADMIN = JdbcClient.create(
      new SimpleDriverDataSource(new org.postgresql.Driver(), DB.url(), "postgres", "postgres"));
  public static final TestJwt IDP = TestJwt.create();
  /** P12: statement files in memory (no object store in the ITs). */
  public static final MemoryStatementFiles FILES = new MemoryStatementFiles();
  static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  static final String CURSOR_KEY;
  /** 4 % base points (excluding fuel), 10 % on food.delivery, a point worth 25 paise. */
  public static final UUID POINTS_CARD = UUID.fromString("01941f29-7c00-7000-8000-00000000b001");
  /** 1 % base cashback, 5 % on shopping.online. */
  public static final UUID CASHBACK_CARD = UUID.fromString("01941f29-7c00-7000-8000-00000000b002");
  /** Retired in the catalog: cannot be added. */
  public static final UUID RETIRED_CARD = UUID.fromString("01941f29-7c00-7000-8000-00000000b003");

  static {
    byte[] b = new byte[32];
    new SecureRandom().nextBytes(b);
    CURSOR_KEY = Base64.getEncoder().encodeToString(b);
  }

  @Value("${local.server.port}")
  protected int port;

  static final java.util.concurrent.atomic.AtomicInteger LAST4 = new java.util.concurrent.atomic.AtomicInteger(1000);

  /** After the context (Flyway has run): the catalog products every test may use. */
  @org.junit.jupiter.api.BeforeEach
  void products() {
    ensureProducts();
  }

  @TestConfiguration(proxyBeanMethods = false)
  public static class TestIdp {
    @Bean
    JwtVerifier testJwtVerifier() {
      return IDP.verifier("portfolio", Clock.systemUTC());
    }

    @Bean
    @org.springframework.context.annotation.Primary
    in.dexter.portfolio.app.StatementFiles testStatementFiles() {
      return FILES;
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", DB::url);
    r.add("spring.datasource.password", () -> ServiceDb.PW);
    r.add("spring.flyway.password", () -> ServiceDb.PW);
    r.add("dexter.cursor.keys.c1", () -> CURSOR_KEY);
    r.add("dexter.portfolio.relay.enabled", () -> "false");
    r.add("dexter.portfolio.consumers.enabled", () -> "false");
    r.add("dexter.portfolio.closer.enabled", () -> "false");
    // P12: a short Mode B lease so the submit-recovery IT can let it expire
    r.add("dexter.portfolio.statements.submit-lease", () -> "1s");
  }

  /** The catalog products the tests use (idempotent; after Flyway, so call from a test or @BeforeEach). */
  public static void ensureProducts() {
    product(POINTS_CARD, "test-points-card", "MOCK_Test Points Card", "visa", 50_000, "published",
        "[{\"categoryPath\":null,\"merchantId\":null,\"rewardType\":\"points\",\"rateBps\":400,"
            + "\"pointValueMilliPaise\":25000,\"minTxnPaise\":0,\"excludedPaths\":[\"fuel\"],\"priority\":10},"
            + "{\"categoryPath\":\"food.delivery\",\"merchantId\":null,\"rewardType\":\"points\",\"rateBps\":1000,"
            + "\"pointValueMilliPaise\":25000,\"minTxnPaise\":0,\"excludedPaths\":[],\"priority\":100}]");
    product(CASHBACK_CARD, "test-cashback-card", "MOCK_Test Cashback Card", "rupay", 0, "published",
        "[{\"categoryPath\":null,\"merchantId\":null,\"rewardType\":\"cashback\",\"rateBps\":100,"
            + "\"pointValueMilliPaise\":0,\"minTxnPaise\":0,\"excludedPaths\":[],\"priority\":10},"
            + "{\"categoryPath\":\"shopping.online\",\"merchantId\":null,\"rewardType\":\"cashback\",\"rateBps\":500,"
            + "\"pointValueMilliPaise\":0,\"minTxnPaise\":0,\"excludedPaths\":[],\"priority\":100}]");
    product(RETIRED_CARD, "test-retired-card", "MOCK_Test Retired Card", "visa", 0, "retired", "[]");
  }

  static void product(UUID id, String slug, String name, String network, long annualFee, String status, String rules) {
    String snapshot = "{\"id\":\"" + id + "\",\"slug\":\"" + slug + "\",\"name\":\"" + name + "\",\"issuerCode\":"
        + "\"MOCK_TEST\",\"network\":\"" + network + "\",\"tier\":\"entry\",\"annualFeePaise\":" + annualFee
        + ",\"rules\":" + rules + "}";
    ADMIN.sql("INSERT INTO portfolio.catalog_products_proj (card_product_id, slug, name, issuer_code, network, tier,"
            + " annual_fee_paise, status, current_version, snapshot, projected_version) VALUES (:id, :slug, :name,"
            + " 'MOCK_TEST', :net, 'entry', :fee, :status, 1, CAST(:snap AS jsonb), 0) ON CONFLICT DO NOTHING")
        .param("id", id).param("slug", slug).param("name", name).param("net", network).param("fee", annualFee)
        .param("status", status).param("snap", snapshot).update();
  }

  public static String token(UUID account) {
    return IDP.user(account).audience("portfolio").sign();
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

  public Res get(String path, UUID account) {
    return send("GET", path, null, null, auth(account));
  }

  /** POST with a fresh Idempotency-Key. */
  public Res post(String path, UUID account, Object body) {
    return send("POST", path, body == null ? null : JSON.writeValueAsString(body), "application/json",
        auth(account, "Idempotency-Key", UUID.randomUUID().toString()));
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

  // ------------------------------------------------------------------------------------------- flows

  public static Map<String, Object> card(UUID product, String last4, int statementDay) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("cardProductId", product);
    m.put("last4", last4);
    m.put("statementDay", statementDay);
    return m;
  }

  /** A new card for the account (the points product); returns its id. */
  public UUID newCard(UUID account, UUID product) {
    Res r = post("/v1/cards", account, card(product, String.format("%04d", LAST4.incrementAndGet() % 10_000), 15));
    if (r.status() != 201) {
      throw new AssertionError(r.toString());
    }
    return UUID.fromString(r.json().get("id").asString());
  }

  public static Map<String, Object> txn(String postedAt, long paise, String kind, String description,
      String category) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("postedAt", postedAt);
    m.put("amountPaise", paise);
    m.put("kind", kind);
    m.put("description", description);
    m.put("categoryPath", category);
    return m;
  }

  public Res record(UUID account, UUID card, Map<String, Object> body) {
    return post("/v1/cards/" + card + "/transactions", account, body);
  }

  public static long outboxRows(UUID account, String type) {
    return ADMIN.sql("SELECT count(1) FROM portfolio.outbox WHERE message_key = :k AND event_type = :t")
        .param("k", account.toString()).param("t", type).query(Long.class).single();
  }
}
