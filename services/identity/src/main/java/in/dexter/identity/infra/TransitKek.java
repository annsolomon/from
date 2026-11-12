package in.dexter.identity.infra;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The identity KEK in Vault Transit (P19, ADR-0026): {@code POST /v1/transit/encrypt/<key>} and {@code decrypt},
 * AES-256-GCM in Vault with the caller's associated data (account id, signing-key id, ...), so a wrapped key copied
 * to another row fails to unwrap exactly as with a local KEK. The stored form is Vault's {@code vault:v<n>:...}
 * string (UTF-8): the version lets {@code transit/keys/<key>/rotate} + {@code rewrap} rotate without this code.
 *
 * <p>The token is read through a supplier on every call, so a Vault Agent sink file (or a renewed token) is picked
 * up without a restart. One retry on an I/O error; Vault errors are not retried.
 */
public final class TransitKek implements KekRing.RemoteKek {
  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private final HttpClient http;
  private final URI base;
  private final String key;
  private final Supplier<String> token;
  private final Duration timeout;

  public TransitKek(String address, String key, Supplier<String> token, Duration timeout) {
    this.base = URI.create(address.endsWith("/") ? address : address + "/");
    this.key = Objects.requireNonNull(key, "key");
    if (!key.matches("[A-Za-z0-9_-]{1,24}")) {
      throw new IllegalArgumentException("transit key name must be 1-24 of [A-Za-z0-9_-]: " + key);
    }
    this.token = token;
    this.timeout = timeout;
    this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  @Override
  public String id() {
    return KekRing.TRANSIT_PREFIX + key;
  }

  @Override
  public byte[] wrap(byte[] aad, byte[] plaintext) {
    JsonNode data = call("encrypt", Map.of("plaintext", b64(plaintext), "associated_data", b64(aad)));
    String ct = text(data.path("ciphertext"));
    if (!ct.startsWith("vault:v")) {
      throw new IllegalStateException("Vault transit encrypt returned no ciphertext");
    }
    return ct.getBytes(StandardCharsets.US_ASCII);
  }

  @Override
  public byte[] unwrap(byte[] aad, byte[] sealed) {
    String ct = new String(sealed, StandardCharsets.US_ASCII);
    JsonNode data = call("decrypt", Map.of("ciphertext", ct, "associated_data", b64(aad)));
    return Base64.getDecoder().decode(text(data.path("plaintext")));
  }

  private JsonNode call(String op, Map<String, String> body) {
    HttpRequest req = HttpRequest.newBuilder(base.resolve("v1/transit/" + op + "/" + key))
        .timeout(timeout)
        .header("X-Vault-Token", token.get())
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
        .build();
    HttpResponse<String> res;
    try {
      res = send(req);
    } catch (IOException first) {
      try {
        res = send(req);
      } catch (IOException e) {
        throw new IllegalStateException("Vault transit " + op + " unreachable: " + e, e);
      }
    }
    if (res.statusCode() / 100 != 2) {
      // Vault's error text never contains the plaintext or the key
      throw new IllegalStateException("Vault transit " + op + " failed: HTTP " + res.statusCode() + " "
          + abbreviate(res.body()));
    }
    return JSON.readTree(res.body()).path("data");
  }

  private HttpResponse<String> send(HttpRequest req) throws IOException {
    try {
      return http.send(req, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted", e);
    }
  }

  private static String text(JsonNode n) {
    return n.isMissingNode() || n.isNull() ? "" : n.asString();
  }

  private static String b64(byte[] b) {
    return Base64.getEncoder().encodeToString(b);
  }

  private static String abbreviate(String s) {
    return s.length() > 300 ? s.substring(0, 300) + "..." : s;
  }
}
