package in.dexter.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import in.dexter.gateway.support.GatewayIT;
import in.dexter.gateway.support.RoutesDoc;
import in.dexter.gateway.support.Tokens;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * MASTER_PLAN P06 "route table test": every documented example reaches the right service with its path unchanged;
 * token routes are 401 without a token and public ones reach the service without; unknown paths are 404 even with
 * no token (the old 404/401 confusion, fixed by construction).
 */
class RouteTableIT extends GatewayIT {

  @Test
  void everyDocumentedPathReachesItsService() {
    String token = Tokens.user();
    List<RoutesDoc.Row> rows = RoutesDoc.rows().stream().filter(r -> !r.dev()).toList();
    for (RoutesDoc.Row r : rows) {
      String body = r.method().equals("GET") || r.method().equals("DELETE") ? null : "{}";
      Res res = r.publicAccess()
          ? send(r.method(), r.path(), body, "X-Forwarded-For", ip())
          : send(r.method(), r.path(), body, auth(token, "X-Forwarded-For", ip()));
      assertThat(res.status()).as(r + " → " + res).isEqualTo(200);
      assertThat(res.header("X-Backend")).as(r.toString()).isEqualTo(r.service());
      List<LoggedRequest> seen = BACKENDS.get(r.service()).findAll(anyRequestedFor(urlEqualTo(r.path())));
      assertThat(seen).as(r + " path forwarded unchanged").isNotEmpty();
      assertThat(seen.getLast().getMethod().getName()).isEqualTo(r.method());
      if (!r.publicAccess()) {
        assertThat(seen.getLast().getHeader("Authorization")).as("token forwarded").isEqualTo("Bearer " + token);
      }
    }
  }

  @Test
  void tokenRoutesAre401WithoutATokenAndPublicRoutesAreNot() {
    for (RoutesDoc.Row r : RoutesDoc.rows()) {
      if (r.dev()) {
        continue;
      }
      String body = r.method().equals("GET") || r.method().equals("DELETE") ? null : "{}";
      Res res = send(r.method(), r.path(), body, "X-Forwarded-For", ip());
      if (r.publicAccess()) {
        assertThat(res.status()).as(r + " → " + res).isEqualTo(200);
      } else {
        assertThat(res.status()).as(r + " → " + res).isEqualTo(401);
        assertThat(res.body()).contains("\"code\":\"UNAUTHENTICATED\"");
        assertThat(res.header("WWW-Authenticate")).startsWith("Bearer");
      }
    }
  }

  @Test
  void unknownPathsAre404WithOrWithoutAToken() {
    for (String p : List.of("/v1/nope", "/v1/expensesx", "/oauth2/token", "/v1/admin/unknown", "/_dev/p04/keys")) {
      Res anon = send("GET", p, null, "X-Forwarded-For", ip());
      assertThat(anon.status()).as(p + " " + anon).isEqualTo(404);
      assertThat(anon.body()).contains("\"code\":\"NOT_FOUND\"").contains("\"requestId\"");
      assertThat(anon.header("Content-Type")).startsWith("application/problem+json");
      assertThat(send("GET", p, null, auth(Tokens.user(), "X-Forwarded-For", ip())).status()).isEqualTo(404);
    }
  }

  @Test
  void theGatewaysOwnHealthNeedsNoToken() {
    Res h = send("GET", "/actuator/health", null);
    assertThat(h.status()).isEqualTo(200);
    assertThat(h.body()).contains("\"status\":\"UP\"");
  }
}
