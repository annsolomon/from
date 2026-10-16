package in.dexter.security.jwt;

import com.nimbusds.jose.jwk.JWKSet;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.util.Objects;

/**
 * Fetches identity's JWKS over HTTP with the plan's I/O limits (MASTER_PLAN 8.2: 2 s connect, 3 s
 * read) and a 64 KB body cap. Redirects are not followed. Only public keys are kept.
 */
public final class HttpJwksSource implements JwksSource {
  static final int MAX_BYTES = 64 * 1024;
  private final URI uri;
  private final HttpClient http;
  private final Duration readTimeout;

  public HttpJwksSource(URI uri) {
    this(uri, HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(), Duration.ofSeconds(3));
  }

  public HttpJwksSource(URI uri, HttpClient http, Duration readTimeout) {
    this.uri = Objects.requireNonNull(uri, "uri");
    this.http = Objects.requireNonNull(http, "http");
    this.readTimeout = Objects.requireNonNull(readTimeout, "readTimeout");
    String scheme = uri.getScheme();
    if (!"https".equals(scheme) && !"http".equals(scheme)) {
      throw new IllegalArgumentException("JWKS uri must be http(s): " + uri);
    }
  }

  @Override
  public JWKSet fetch() throws IOException {
    HttpRequest req = HttpRequest.newBuilder(uri).timeout(readTimeout).header("Accept", "application/json").GET().build();
    HttpResponse<InputStream> res;
    try {
      res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while fetching JWKS", e);
    }
    try (InputStream in = res.body()) {
      if (res.statusCode() != 200) {
        throw new IOException("JWKS endpoint answered " + res.statusCode());
      }
      byte[] body = in.readNBytes(MAX_BYTES + 1);
      if (body.length > MAX_BYTES) {
        throw new IOException("JWKS body larger than " + MAX_BYTES + " bytes");
      }
      return JWKSet.parse(new String(body, StandardCharsets.UTF_8)).toPublicJWKSet();
    } catch (ParseException e) {
      throw new IOException("JWKS is not a valid JWK set", e);
    }
  }

  @Override
  public String toString() {
    return "HttpJwksSource[" + uri + "]";
  }
}
