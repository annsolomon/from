package in.dexter.security.client;

import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Client-credentials access tokens for service-to-service calls (MASTER_PLAN 7.2): one token per
 * target audience, cached until {@code refreshSkew} before it expires, fetched by one thread at a
 * time per audience (single flight). Call {@link #invalidate(String)} when the target answers 401.
 *
 * <p>Wire contract with identity (P04): {@code POST <token-uri>} with HTTP Basic client
 * authentication (RFC 6749 2.3.1) and form body {@code grant_type=client_credentials&audience=<svc>};
 * the response is {@code {"access_token", "token_type":"Bearer", "expires_in"}}.
 */
public final class ServiceTokenClient {
  private static final Logger log = LoggerFactory.getLogger(ServiceTokenClient.class);
  private static final Pattern AUDIENCE = Pattern.compile("[a-z][a-z0-9-]{1,31}");
  private static final int MAX_BYTES = 16 * 1024;

  private record Cached(String token, Instant refreshAt) {}

  private final URI tokenUri;
  private final String authorization;
  private final HttpClient http;
  private final JsonMapper json;
  private final Clock clock;
  private final Duration refreshSkew;
  private final Map<String, Cached> cache = new ConcurrentHashMap<>();
  private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

  public ServiceTokenClient(URI tokenUri, String clientId, String clientSecret, HttpClient http, JsonMapper json,
      Clock clock, Duration refreshSkew) {
    this.tokenUri = Objects.requireNonNull(tokenUri, "tokenUri");
    Objects.requireNonNull(clientId, "clientId");
    Objects.requireNonNull(clientSecret, "clientSecret");
    this.authorization = "Basic " + Base64.getEncoder().encodeToString(
        (form(clientId) + ":" + form(clientSecret)).getBytes(StandardCharsets.UTF_8));
    this.http = Objects.requireNonNull(http, "http");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.refreshSkew = Objects.requireNonNull(refreshSkew, "refreshSkew");
  }

  /** HTTP client with the plan's timeouts (2 s connect); no redirects. */
  public static HttpClient defaultHttpClient() {
    return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
  }

  /** A valid access token for calling {@code audience} (a service name such as {@code catalog}). */
  public String tokenFor(String audience) {
    if (audience == null || !AUDIENCE.matcher(audience).matches()) {
      throw new IllegalArgumentException("audience must be a service name");
    }
    Cached c = cache.get(audience);
    if (c != null && clock.instant().isBefore(c.refreshAt())) {
      return c.token();
    }
    ReentrantLock lock = locks.computeIfAbsent(audience, a -> new ReentrantLock());
    lock.lock();
    try {
      c = cache.get(audience);
      if (c != null && clock.instant().isBefore(c.refreshAt())) {
        return c.token();
      }
      Cached fresh = fetch(audience);
      cache.put(audience, fresh);
      return fresh.token();
    } finally {
      lock.unlock();
    }
  }

  /** Drops the cached token for {@code audience} (the target rejected it). */
  public void invalidate(String audience) {
    cache.remove(audience);
  }

  private Cached fetch(String audience) {
    Instant requestedAt = clock.instant();
    HttpRequest req = HttpRequest.newBuilder(tokenUri)
        .timeout(Duration.ofSeconds(3))
        .header("Authorization", authorization)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&audience=" + form(audience)))
        .build();
    try {
      HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
      byte[] body;
      try (InputStream in = res.body()) {
        body = in.readNBytes(MAX_BYTES + 1);
      }
      if (res.statusCode() != 200 || body.length > MAX_BYTES) {
        log.warn("SERVICE_TOKEN_FAILED audience={} status={}", audience, res.statusCode());
        throw unavailable(null);
      }
      JsonNode n = json.readTree(new String(body, StandardCharsets.UTF_8));
      String token = n.path("access_token").asString("");
      String type = n.path("token_type").asString("");
      long expiresIn = n.path("expires_in").asLong(0L);
      if (token.isEmpty() || !"bearer".equalsIgnoreCase(type) || expiresIn <= 0) {
        log.warn("SERVICE_TOKEN_FAILED audience={} reason=bad-response", audience);
        throw unavailable(null);
      }
      Instant refreshAt = requestedAt.plusSeconds(expiresIn).minus(refreshSkew);
      if (!refreshAt.isAfter(requestedAt)) {
        refreshAt = requestedAt.plusSeconds(Math.max(1, expiresIn / 2));
      }
      return new Cached(token, refreshAt);
    } catch (IOException e) {
      log.warn("SERVICE_TOKEN_FAILED audience={} error={}", audience, e.toString());
      throw unavailable(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unavailable(e);
    } catch (RuntimeException e) {
      if (e instanceof DexterException d) {
        throw d;
      }
      log.warn("SERVICE_TOKEN_FAILED audience={} error={}", audience, e.toString());
      throw unavailable(e);
    }
  }

  private static DexterException unavailable(@org.jspecify.annotations.Nullable Throwable cause) {
    return new DexterException(CoreErrors.SERVICE_UNAVAILABLE, "Could not obtain a service token", cause);
  }

  private static String form(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
