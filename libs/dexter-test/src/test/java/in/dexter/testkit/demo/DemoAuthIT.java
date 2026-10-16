package in.dexter.testkit.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import in.dexter.testkit.contract.HttpResult;
import in.dexter.testkit.demo.config.DemoApp;
import in.dexter.testkit.support.Mvc;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The demo end to end with dexter-security (P02b): the tenant comes only from the verified token,
 * and RLS isolates accounts by that tenant. Template for every service's auth IT.
 */
class DemoAuthIT {
  static final String BODY = "{\"title\":\"auth note\",\"kind\":\"note\",\"amountPaise\":100}";
  final DemoApp app = DemoApp.get();

  HttpResult create(String authorization) throws Exception {
    var req = post("/demo/notes").contentType(MediaType.APPLICATION_JSON).content(BODY)
        .header("Idempotency-Key", UUID.randomUUID().toString());
    if (authorization != null) {
      req.header("Authorization", authorization);
    }
    return Mvc.call(app.mvc, req);
  }

  @Test
  void noTokenIs401AndWritesNothing() throws Exception {
    HttpResult r = create(null);
    assertThat(r.status()).isEqualTo(401);
    assertThat(r.body()).contains("\"code\":\"UNAUTHENTICATED\"").contains("requestId");
  }

  @Test
  void accountIdHeadersAreIgnored() throws Exception {
    UUID victim = UUID.randomUUID();
    HttpResult r = Mvc.call(app.mvc, get("/demo/notes").header("X-Demo-Account", victim.toString())
        .header("X-Account-Id", victim.toString()));
    assertThat(r.status()).isEqualTo(401);
  }

  @Test
  void tokenForAnotherServiceIsRejected() throws Exception {
    String ledgerToken = "Bearer " + app.idp.user(UUID.randomUUID()).audience("ledger").sign();
    assertThat(create(ledgerToken).status()).isEqualTo(401);
  }

  @Test
  void serviceTokenCannotActAsAUser() throws Exception {
    String svc = "Bearer " + app.idp.service("reco").audience(DemoApp.AUDIENCE).sign();
    assertThat(create(svc).status()).isEqualTo(403);
  }

  @Test
  void eachTokenSeesOnlyItsOwnNotes() throws Exception {
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();
    assertThat(create(app.bearer(alice)).status()).isEqualTo(201);
    assertThat(create(app.bearer(alice)).status()).isEqualTo(201);
    assertThat(create(app.bearer(bob)).status()).isEqualTo(201);

    HttpResult aliceList = Mvc.call(app.mvc, get("/demo/notes").header("Authorization", app.bearer(alice)));
    HttpResult bobList = Mvc.call(app.mvc, get("/demo/notes").header("Authorization", app.bearer(bob)));
    assertThat(aliceList.status()).isEqualTo(200);
    assertThat(count(aliceList.body())).isEqualTo(2);
    assertThat(count(bobList.body())).isEqualTo(1);
    assertThat(app.admin.sql("SELECT count(1) FROM demo.notes WHERE account_id = :a").param("a", alice)
        .query(Long.class).single()).isEqualTo(2L);
  }

  private static int count(String json) {
    return json.split("\"title\"", -1).length - 1;
  }
}
