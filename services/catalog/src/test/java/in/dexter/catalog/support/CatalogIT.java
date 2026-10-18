package in.dexter.catalog.support;

import in.dexter.security.jwt.JwtVerifier;
import in.dexter.testkit.containers.ServiceDb;
import in.dexter.testkit.containers.TestPostgres;
import in.dexter.testkit.security.TestJwt;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import in.dexter.catalog.app.PageFetcher;
import in.dexter.catalog.app.SnapshotStore;
import in.dexter.catalog.domain.FetchPolicy;
import in.dexter.catalog.infra.SafeFetcher;
import java.net.InetAddress;
import java.net.UnknownHostException;
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
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base of catalog's integration tests: the real application on a random port, Flyway (as catalog_owner) on a
 * Testcontainers Postgres database laid out like infra, the app pool as catalog_app, the admin pool as catalog_admin,
 * the relay on (to the test Kafka), and a test identity provider ({@link TestJwt}) whose verifier replaces the JWKS
 * one. One Spring context for all subclasses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CatalogIT.TestIdp.class)
public abstract class CatalogIT {
  public static final JsonMapper JSON = JsonMapper.builder().build();
  public static final ServiceDb DB = TestPostgres.flywayDb("catalog");
  /** Superuser: assertions and bulk seeding only (bypasses RLS). */
  public static final JdbcClient ADMIN = JdbcClient.create(
      new SimpleDriverDataSource(new org.postgresql.Driver(), DB.url(), "postgres", "postgres"));
  public static final TestJwt IDP = TestJwt.create();
  /** P10: the local issuer site the collector fetches (its 127.0.0.1:port is the one dev host). */
  public static final MockIssuerSite SITE = new MockIssuerSite();
  public static final MemorySnapshotStore SNAPSHOTS = new MemorySnapshotStore();
  /**
   * Invented issuer hosts (allowlisted as {@code issuer.test}) and the addresses they "resolve" to: the SSRF tests
   * point allowlisted names at private ranges.
   */
  public static final Map<String, String> TEST_DNS = Map.of("meta.issuer.test", "169.254.169.254",
      "loop.issuer.test", "127.0.0.1", "internal.issuer.test", "10.0.0.7", "ula.issuer.test", "fd00::7",
      "mapped.issuer.test", "::ffff:192.168.1.9", "public.issuer.test", "93.184.215.14");
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
      return IDP.verifier("catalog", Clock.systemUTC());
    }

    /** The production fetcher with the test DNS for *.issuer.test (everything else resolves normally). */
    @Bean
    @Primary
    PageFetcher testPageFetcher(FetchPolicy policy) {
      return new SafeFetcher(policy, host -> {
        String ip = TEST_DNS.get(host);
        if (ip != null) {
          return List.of(InetAddress.getByName(ip));
        }
        if (host.endsWith(".issuer.test") || host.equals("issuer.test")) {
          throw new UnknownHostException(host);
        }
        return SafeFetcher.SYSTEM.resolve(host);
      }, 200_000, Duration.ofSeconds(3), 3);
    }

    @Bean
    @Primary
    SnapshotStore testSnapshotStore() {
      return SNAPSHOTS;
    }

  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", DB::url);
    r.add("spring.datasource.password", () -> ServiceDb.PW);
    r.add("spring.flyway.password", () -> ServiceDb.PW);
    r.add("dexter.cursor.keys.c1", () -> CURSOR_KEY);
    r.add("dexter.catalog.admin.db-url", DB::url);
    r.add("dexter.catalog.admin.db-password", () -> ServiceDb.PW);
    // the relay publishes to the test Kafka: the projection test consumes the compacted card topic
    r.add("dexter.catalog.relay.enabled", () -> "true");
    r.add("dexter.catalog.relay.url", DB::url);
    r.add("dexter.catalog.relay.username", () -> "catalog_relay");
    r.add("dexter.catalog.relay.password", () -> ServiceDb.PW);
    r.add("dexter.catalog.relay.bootstrap-servers", in.dexter.testkit.containers.TestKafka::bootstrapServers);
    // P10: tests drive the collector themselves (no background worker), politeness short but measurable
    r.add("dexter.catalog.ingestion.worker-enabled", () -> "false");
    r.add("dexter.catalog.ingestion.allowed-hosts", () -> "issuer.test");
    r.add("dexter.catalog.ingestion.dev-hosts", SITE::hostPort);
    r.add("dexter.catalog.ingestion.politeness", () -> "150ms");
  }

  public static String token(UUID account) {
    return IDP.user(account).audience("catalog").sign();
  }

  /** An admin-console token (scope admin, MFA) with the CATALOG_EDITOR role and the given permissions. */
  public static String editorToken(UUID admin, String... perms) {
    return IDP.admin(admin, "CATALOG_EDITOR").scope("admin").audience("catalog").permissions(perms).sign();
  }

  public static final UUID EDITOR = UUID.fromString("0000000e-0000-7000-8000-00000000ed17");

  /** A full catalog editor (read, write, publish). */
  public static String editor() {
    return editorToken(EDITOR, "catalog:read", "catalog:write", "catalog:publish");
  }

  /** Anonymous request (the public catalog). */
  public Res anon(String method, String path) {
    return send(method, path, null, null);
  }

  public Res as(String bearer, String method, String path, Object body, String... headers) {
    String[] h = new String[headers.length + 2];
    h[0] = "Authorization";
    h[1] = "Bearer " + bearer;
    System.arraycopy(headers, 0, h, 2, headers.length);
    return send(method, path, body == null ? null : JSON.writeValueAsString(body), "application/json", h);
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

  // ------------------------------------------------------------------------------------------- catalog flows

  /** A new issuer (unique code) through the admin API. */
  public String newIssuer() {
    String code = "T_" + Cards.suffix().toUpperCase(java.util.Locale.ROOT);
    Res r = as(editor(), "POST", "/v1/admin/catalog/issuers", Cards.issuer(code));
    if (r.status() != 201) {
      throw new AssertionError(r.toString());
    }
    return code;
  }

  /** A new draft card; returns its id. */
  public UUID newCard(Map<String, Object> doc) {
    Res r = as(editor(), "POST", "/v1/admin/catalog/cards", doc);
    if (r.status() != 201) {
      throw new AssertionError(r.toString());
    }
    return UUID.fromString(r.json().get("cardId").asString());
  }

  public Res publish(UUID id) {
    return as(editor(), "POST", "/v1/admin/catalog/cards/" + id + ":publish", null);
  }

  /** Issuer + card + first publish; returns the card id. */
  public UUID publishedCard(String slug, String name, String network, long annualFee) {
    UUID id = newCard(Cards.doc(slug, name, newIssuer(), network, annualFee));
    Res p = publish(id);
    if (p.status() != 200) {
      throw new AssertionError(p.toString());
    }
    return id;
  }
}
