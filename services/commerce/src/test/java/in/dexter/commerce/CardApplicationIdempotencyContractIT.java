package in.dexter.commerce;

import in.dexter.commerce.app.ApplicationService;
import in.dexter.commerce.support.CommerceIT;
import in.dexter.testkit.contract.HttpResult;
import in.dexter.testkit.contract.IdempotencyContract;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * MASTER_PLAN 5.5 on POST /v1/card-applications (Mode A, key required). The business row is the application and the
 * event is commerce.application.status-changed; body(n) alternates the two published cards, so a different body is a
 * different card (never the one-open-per-card conflict inside one case).
 */
class CardApplicationIdempotencyContractIT extends CommerceIT {
  @Autowired
  ApplicationService applications;

  @BeforeEach
  void cards() {
    ensureCards();
  }

  @Nested
  class Apply extends IdempotencyContract {
    @Override
    protected HttpResult mutate(UUID account, UUID key, String body) {
      Res r = key == null ? send("POST", "/v1/card-applications", body, auth(account))
          : send("POST", "/v1/card-applications", body, auth(account, "Idempotency-Key", key.toString()));
      return new HttpResult(r.status(), r.headers(), r.body());
    }

    @Override
    protected String body(int n) {
      return "{\"cardProductId\":\"" + (n % 2 == 1 ? CARD_A : CARD_B) + "\"}";
    }

    @Override
    protected long businessRows(UUID account) {
      return count("SELECT count(1) FROM commerce.card_applications WHERE account_id = ?", account);
    }

    @Override
    protected long outboxRows(UUID account) {
      return CommerceIT.outboxRows(account, "commerce.application.status-changed");
    }

    @Override
    protected void failNextMutation() {
      applications.failNextApply();
    }
  }
}
