package in.dexter.testkit.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import in.dexter.testkit.contract.HttpResult;
import in.dexter.testkit.contract.IdempotencyContract;
import in.dexter.testkit.demo.config.DemoApp;
import in.dexter.testkit.support.Mvc;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** MASTER_PLAN 5.5 on POST /demo/notes (key required). */
class DemoIdempotencyContractIT extends IdempotencyContract {
  final DemoApp app = DemoApp.get();

  @Override
  protected HttpResult mutate(UUID account, UUID key, String body) throws Exception {
    MockHttpServletRequestBuilder req = post("/demo/notes").contentType(MediaType.APPLICATION_JSON).content(body)
        .header("Authorization", app.bearer(account));
    if (key != null) {
      req.header("Idempotency-Key", key.toString());
    }
    return Mvc.call(app.mvc, req);
  }

  @Override
  protected String body(int n) {
    return "{\"title\":\"note " + n + "\",\"kind\":\"note\",\"amountPaise\":" + (1000 * n) + "}";
  }

  @Override
  protected long businessRows(UUID account) {
    return app.admin.sql("SELECT count(1) FROM demo.notes WHERE account_id = :a").param("a", account)
        .query(Long.class).single();
  }

  @Override
  protected long outboxRows(UUID account) {
    return app.admin.sql("SELECT count(1) FROM demo.outbox WHERE message_key = :a").param("a", account.toString())
        .query(Long.class).single();
  }

  @Override
  protected void failNextMutation() {
    app.service.failNextCreate();
  }
}
