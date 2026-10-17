package in.dexter.gateway.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import in.dexter.gateway.domain.RouteTable;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared base of the gateway ITs (tests extend {@link GatewayIT}; DegradedIT extends this directly with dead
 * Redis/Kafka addresses, because a subclass cannot reliably override a superclass's dynamic property): the real gateway on a random port in front of one WireMock backend per service (each
 * answers 200 with {@code X-Backend: <service>}; identity also serves the test JWKS), Redis with the restricted ACL
 * user {@code gateway} from infra/redis/acl.tmpl, and Kafka (same images as compose). Every test uses its own client
 * IP ({@link #ip()}, believed from X-Forwarded-For because the test client is a trusted proxy) so rate-limit buckets
 * never leak between tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class GatewayBase {
  public static final Map<String, WireMockServer> BACKENDS = new LinkedHashMap<>();
  public static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
      .withExposedPorts(6379);
  public static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");
  static final String REDIS_PW = "gateway-it-pw";
  static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  static final AtomicInteger IPS = new AtomicInteger(1);

  static {
    for (String svc : RouteTable.SERVICES) {
      WireMockServer w = new WireMockServer(WireMockConfiguration.options().dynamicPort());
      w.start();
      BACKENDS.put(svc, w);
    }
    resetBackends();
    REDIS.start();
    KAFKA.start();
    applyAcl();
  }

  @Value("${local.server.port}")
  protected int port;

  @DynamicPropertySource
  static void gatewayProps(DynamicPropertyRegistry r) {
    BACKENDS.forEach((svc, w) -> r.add("dexter.gateway.services." + svc, w::baseUrl));
    r.add("dexter.security.jwks-uri", () -> BACKENDS.get("identity").baseUrl() + "/.well-known/jwks.json");
    r.add("dexter.security.issuer", () -> Tokens.ISSUER);
    r.add("dexter.gateway.activity.sample-rate", () -> "1.0");
    r.add("dexter.gateway.cors.allowed-origins", () -> "http://localhost:8099");
    r.add("dexter.gateway.response-timeout", () -> "3s");
  }

  /** Every backend: 200 {"service":...} + X-Backend for any request; identity also serves the JWKS. */
  public static void resetBackends() {
    BACKENDS.forEach((svc, w) -> {
      w.resetAll();
      w.stubFor(any(anyUrl()).atPriority(10).willReturn(aResponse().withStatus(200)
          .withHeader("Content-Type", "application/json").withHeader("X-Backend", svc)
          .withBody("{\"service\":\"" + svc + "\"}")));
    });
    BACKENDS.get("identity").stubFor(get(urlEqualTo("/.well-known/jwks.json")).atPriority(1)
        .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
            .withHeader("X-Backend", "identity").withBody(Tokens.jwks())));
  }

  static void applyAcl() {
    RedisStandaloneConfiguration conf = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
    LettuceConnectionFactory f = new LettuceConnectionFactory(conf);
    f.afterPropertiesSet();
    f.start();
    StringRedisTemplate admin = new StringRedisTemplate(f);
    String line = aclLine().replace("{{svc}}", "gateway").replace("{{pw}}", REDIS_PW);
    List<String> tokens = new ArrayList<>(Arrays.asList(line.trim().split("\\s+")));
    List<byte[]> args = new ArrayList<>();
    args.add("SETUSER".getBytes(StandardCharsets.UTF_8));
    args.add("gateway".getBytes(StandardCharsets.UTF_8));
    args.add("reset".getBytes(StandardCharsets.UTF_8));
    tokens.subList(2, tokens.size()).forEach(t -> args.add(t.getBytes(StandardCharsets.UTF_8)));
    admin.execute((RedisCallback<Object>) c -> c.execute("ACL", args.toArray(new byte[0][])));
    // the app logs in as `gateway` with this password; the default user stays for the container itself
    conf.setUsername("gateway");
    conf.setPassword(RedisPassword.of(REDIS_PW));
    f.destroy();
  }

  static String aclLine() {
    try {
      return Files.readAllLines(repoFile("infra/redis/acl.tmpl"), StandardCharsets.UTF_8).stream()
          .filter(l -> l.startsWith("user {{svc}} ")).findFirst().orElseThrow();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public static Path repoFile(String relative) {
    return RoutesDoc.repoFile(relative);
  }

  /** A fresh client IP per call site (TEST-NET-3, never a trusted proxy). */
  public static String ip() {
    int n = IPS.getAndIncrement();
    return "203.0." + (113 + n / 250 % 10) + "." + (1 + n % 250);
  }

  public record Res(int status, Map<String, String> headers, String body, long millis) {
    public String header(String name) {
      return headers.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public String toString() {
      return status + " " + headers + " " + body;
    }
  }

  protected Res send(String method, String path, String body, String... headers) {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .timeout(Duration.ofSeconds(40))
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    if (body != null) {
      b.header("Content-Type", "application/json");
    }
    for (int i = 0; i + 1 < headers.length; i += 2) {
      b.header(headers[i], headers[i + 1]);
    }
    return send(b.build());
  }

  protected Res send(HttpRequest req) {
    long t0 = System.nanoTime();
    try {
      HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
      Map<String, String> h = new LinkedHashMap<>();
      r.headers().map().forEach((k, v) -> h.put(k.toLowerCase(java.util.Locale.ROOT), v.getFirst()));
      return new Res(r.statusCode(), h, r.body(), (System.nanoTime() - t0) / 1_000_000);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  protected URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  public static String[] auth(String token, String... more) {
    String[] h = new String[more.length + 2];
    h[0] = "Authorization";
    h[1] = "Bearer " + token;
    System.arraycopy(more, 0, h, 2, more.length);
    return h;
  }
}
