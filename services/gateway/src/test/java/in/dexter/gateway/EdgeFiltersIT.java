package in.dexter.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import in.dexter.gateway.support.GatewayIT;
import in.dexter.gateway.support.Tokens;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Filters 1–4, 7, 8: request id, header hygiene, body size, JWT pre-check, security headers, CORS. */
class EdgeFiltersIT extends GatewayIT {

  LoggedRequest lastAt(String service, String path) {
    List<LoggedRequest> all = BACKENDS.get(service).findAll(anyRequestedFor(urlPathEqualTo(path)));
    assertThat(all).as(service + " " + path).isNotEmpty();
    return all.getLast();
  }

  @Test
  void requestIdIsKeptOrGeneratedAndReachesTheService() {
    String path = "/v1/cards/" + UUID.randomUUID();
    Res given = send("GET", path, null, auth(Tokens.user(), "X-Request-Id", "trail-abc.1", "X-Forwarded-For", ip()));
    assertThat(given.header("X-Request-Id")).isEqualTo("trail-abc.1");
    assertThat(lastAt("portfolio", path).getHeader("X-Request-Id")).isEqualTo("trail-abc.1");
    Res bad = send("GET", path, null, auth(Tokens.user(), "X-Request-Id", "no spaces <allowed>", "X-Forwarded-For", ip()));
    assertThat(bad.header("X-Request-Id")).matches("[0-9a-f-]{36}");
    Res none = send("GET", "/v1/nope", null);
    assertThat(none.header("X-Request-Id")).isNotBlank();
    assertThat(none.body()).contains(none.header("X-Request-Id"));
  }

  @Test
  void spoofedIdentityHeadersAreStripped() {
    String path = "/v1/budgets/" + UUID.randomUUID();
    Res r = send("GET", path, null, auth(Tokens.user(), "X-Account-Id", UUID.randomUUID().toString(),
        "X-Actor-Role", "SUPER_ADMIN", "X-User-Id", "42", "X-Roles", "SUPER_ADMIN", "X-Tenant-Id", "t1",
        "X-Custom-Ok", "kept", "X-Forwarded-For", ip()));
    assertThat(r.status()).isEqualTo(200);
    LoggedRequest seen = lastAt("planning", path);
    for (String h : List.of("X-Account-Id", "X-Actor-Role", "X-User-Id", "X-Roles", "X-Tenant-Id")) {
      assertThat(seen.containsHeader(h)).as(h + " stripped").isFalse();
    }
    assertThat(seen.getHeader("X-Custom-Ok")).isEqualTo("kept");
    assertThat(seen.getHeader("Authorization")).startsWith("Bearer ");
  }

  @Test
  void publicRouteWorksWithoutAToken() {
    Res r = send("POST", "/v1/auth/login", "{\"email\":\"a@b.c\",\"password\":\"x\"}", "X-Forwarded-For", ip());
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.header("X-Backend")).isEqualTo("identity");
    assertThat(send("GET", "/v1/catalog/cards", null, "X-Forwarded-For", ip()).header("X-Backend")).isEqualTo("catalog");
  }

  @Test
  void badTokensAreRejectedEarly() {
    String path = "/v1/expenses";
    for (String t : List.of(Tokens.expired(), Tokens.wrongAudience(), "not.a.jwt", Tokens.user() + "x")) {
      Res r = send("GET", path, null, auth(t, "X-Forwarded-For", ip()));
      assertThat(r.status()).as(t).isEqualTo(401);
      assertThat(r.body()).contains("UNAUTHENTICATED");
      assertThat(r.header("WWW-Authenticate")).contains("invalid_token");
    }
    Res svc = send("GET", path, null, auth(Tokens.service(), "X-Forwarded-For", ip()));
    assertThat(svc.status()).isEqualTo(403);
    assertThat(svc.body()).contains("\"code\":\"FORBIDDEN\"");
    assertThat(send("GET", path, null, "Authorization", "Basic abc", "X-Forwarded-For", ip()).status()).isEqualTo(401);
  }

  /** Raw HTTP/1.1 so the client keeps going even when the gateway answers before the body is read. */
  String rawPost(String path, boolean chunked, int bytes) throws Exception {
    try (java.net.Socket so = new java.net.Socket("localhost", port)) {
      so.setSoTimeout(20_000);
      java.io.OutputStream out = so.getOutputStream();
      String head = "POST " + path + " HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer " + Tokens.user()
          + "\r\nX-Forwarded-For: " + ip() + "\r\nContent-Type: application/json\r\n"
          + (chunked ? "Transfer-Encoding: chunked\r\n" : "Content-Length: " + bytes + "\r\n") + "\r\n";
      out.write(head.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      byte[] chunk = new byte[16 * 1024];
      java.util.Arrays.fill(chunk, (byte) 'a');
      try {
        for (int sent = 0; sent < bytes; sent += chunk.length) {
          if (chunked) {
            out.write((Integer.toHexString(chunk.length) + "\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            out.write(chunk);
            out.write("\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
          } else {
            out.write(chunk, 0, Math.min(chunk.length, bytes - sent));
          }
        }
        if (chunked) {
          out.write("0\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        out.flush();
      } catch (java.io.IOException earlyAnswer) {
        // the gateway answered and closed before the whole body was sent: read what it said
      }
      java.io.BufferedReader in = new java.io.BufferedReader(
          new java.io.InputStreamReader(so.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
      return in.readLine();
    }
  }

  @Test
  void bodiesOver256KbAre413DeclaredOrStreamed() throws Exception {
    assertThat(rawPost("/v1/expenses", false, 300 * 1024)).as("declared").contains(" 413");
    assertThat(rawPost("/v1/expenses", true, 300 * 1024)).as("chunked").contains(" 413");
    assertThat(rawPost("/v1/expenses", false, 200 * 1024)).as("under the limit").contains(" 200");
  }

  @Test
  void securityHeadersOnEveryResponse() {
    for (Res r : List.of(send("GET", "/v1/catalog/cards", null, "X-Forwarded-For", ip()),
        send("GET", "/v1/nope", null), send("GET", "/v1/expenses", null, "X-Forwarded-For", ip()))) {
      assertThat(r.header("X-Content-Type-Options")).isEqualTo("nosniff");
      assertThat(r.header("Referrer-Policy")).isEqualTo("no-referrer");
      assertThat(r.header("X-Frame-Options")).isEqualTo("DENY");
      assertThat(r.header("Content-Security-Policy")).startsWith("default-src 'none'");
      assertThat(r.header("Strict-Transport-Security")).startsWith("max-age=");
    }
  }

  @Test
  void corsAllowsOnlyTheConfiguredOrigin() {
    Res pre = send(HttpRequest.newBuilder(uri("/v1/expenses")).timeout(Duration.ofSeconds(10))
        .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).header("Origin", "http://localhost:8099")
        .header("Access-Control-Request-Method", "POST")
        .header("Access-Control-Request-Headers", "authorization,idempotency-key,content-type").build());
    assertThat(pre.status()).as("preflight needs no token").isEqualTo(200);
    assertThat(pre.header("Access-Control-Allow-Origin")).isEqualTo("http://localhost:8099");
    assertThat(pre.header("Access-Control-Allow-Headers").toLowerCase()).contains("idempotency-key");
    Res evil = send(HttpRequest.newBuilder(uri("/v1/catalog/cards")).timeout(Duration.ofSeconds(10))
        .header("Origin", "https://evil.example").header("X-Forwarded-For", ip()).GET().build());
    assertThat(evil.status()).isEqualTo(403);
    assertThat(evil.header("Access-Control-Allow-Origin")).isNull();
    Res ok = send(HttpRequest.newBuilder(uri("/v1/catalog/cards")).timeout(Duration.ofSeconds(10))
        .header("Origin", "http://localhost:8099").header("X-Forwarded-For", ip()).GET().build());
    assertThat(ok.status()).isEqualTo(200);
    assertThat(ok.header("Access-Control-Allow-Origin")).isEqualTo("http://localhost:8099");
    assertThat(ok.header("Access-Control-Expose-Headers")).contains("X-Request-Id");
  }
}
