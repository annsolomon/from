package in.dexter.testkit.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.core.time.MutableClock;
import in.dexter.security.client.ServiceTokenClient;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Client-credentials client against a fake identity token endpoint (JDK HttpServer on loopback). */
class ServiceTokenClientTest {
  HttpServer server;
  final AtomicInteger calls = new AtomicInteger();
  final List<String> bodies = new ArrayList<>();
  final Set<String> auths = ConcurrentHashMap.newKeySet();
  volatile int status = 200;
  volatile String response = "{\"access_token\":\"tok-%d\",\"token_type\":\"Bearer\",\"expires_in\":300}";
  MutableClock clock;
  ServiceTokenClient client;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/oauth2/token", ex -> {
      int n = calls.incrementAndGet();
      synchronized (bodies) {
        bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      }
      auths.add(ex.getRequestHeaders().getFirst("Authorization"));
      try {
        Thread.sleep(50); // widen the race window for the single-flight test
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      byte[] out = response.formatted(n).getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(status, out.length);
      try (OutputStream os = ex.getResponseBody()) {
        os.write(out);
      }
    });
    server.setExecutor(Executors.newFixedThreadPool(8));
    server.start();
    clock = new MutableClock(Instant.parse("2026-09-27T10:00:00Z"));
    URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/oauth2/token");
    client = new ServiceTokenClient(uri, "reco", "s3cr:et", ServiceTokenClient.defaultHttpClient(),
        JsonMapper.builder().build(), clock, Duration.ofSeconds(60));
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void fetchesWithBasicAuthAndCachesPerAudience() {
    assertThat(client.tokenFor("catalog")).isEqualTo("tok-1");
    assertThat(client.tokenFor("catalog")).isEqualTo("tok-1");
    assertThat(client.tokenFor("ledger")).isEqualTo("tok-2");
    assertThat(calls.get()).isEqualTo(2);
    assertThat(bodies).contains("grant_type=client_credentials&audience=catalog");
    String expected = "Basic " + Base64.getEncoder().encodeToString("reco:s3cr%3Aet".getBytes(StandardCharsets.UTF_8));
    assertThat(auths).containsExactly(expected);
  }

  @Test
  void refreshesBeforeExpiryAndOnInvalidate() {
    assertThat(client.tokenFor("catalog")).isEqualTo("tok-1");
    clock.advance(Duration.ofSeconds(239));
    assertThat(client.tokenFor("catalog")).isEqualTo("tok-1");
    clock.advance(Duration.ofSeconds(1)); // 300 s TTL - 60 s skew
    assertThat(client.tokenFor("catalog")).isEqualTo("tok-2");
    client.invalidate("catalog");
    assertThat(client.tokenFor("catalog")).isEqualTo("tok-3");
  }

  @Test
  void concurrentCallersShareOneFetch() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(16);
    try {
      CountDownLatch go = new CountDownLatch(1);
      List<Future<String>> results = new ArrayList<>();
      for (int i = 0; i < 16; i++) {
        results.add(pool.submit(() -> {
          go.await();
          return client.tokenFor("catalog");
        }));
      }
      go.countDown();
      for (Future<String> f : results) {
        assertThat(f.get()).isEqualTo("tok-1");
      }
      assertThat(calls.get()).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void failuresAreServiceUnavailableAndNotCached() {
    status = 401;
    assertThatThrownBy(() -> client.tokenFor("catalog")).isInstanceOf(DexterException.class)
        .satisfies(e -> assertThat(((DexterException) e).errorCode()).isEqualTo(CoreErrors.SERVICE_UNAVAILABLE));
    status = 200;
    response = "{\"token_type\":\"Bearer\",\"expires_in\":300}";
    assertThatThrownBy(() -> client.tokenFor("catalog")).isInstanceOf(DexterException.class);
    response = "{\"access_token\":\"tok-%d\",\"token_type\":\"Bearer\",\"expires_in\":300}";
    assertThat(client.tokenFor("catalog")).isEqualTo("tok-3");
  }

  @Test
  void audienceMustBeAServiceName() {
    assertThatThrownBy(() -> client.tokenFor("../admin")).isInstanceOf(IllegalArgumentException.class);
  }
}
