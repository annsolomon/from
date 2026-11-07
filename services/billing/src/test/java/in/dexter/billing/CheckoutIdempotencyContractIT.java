package in.dexter.billing;

import in.dexter.billing.support.BillingIT;
import in.dexter.testkit.contract.HttpResult;
import in.dexter.testkit.contract.IdempotencyContract;
import java.util.UUID;
import org.junit.jupiter.api.Nested;

/**
 * MASTER_PLAN 5.5 on POST /v1/subscriptions/checkout - Mode B, with the provider in the middle. The "business row" is
 * the subscription and the event is billing.subscription.changed; a parallel duplicate that meets the first attempt
 * inside its lease is told 409 IDEMPOTENCY_IN_PROGRESS, which is the contract's accepted non-2xx answer.
 */
class CheckoutIdempotencyContractIT extends BillingIT {

  @Nested
  class Checkout extends IdempotencyContract {
    @Override
    protected HttpResult mutate(UUID account, UUID key, String body) {
      Res r = key == null ? send("POST", "/v1/subscriptions/checkout", body, "application/json", auth(account))
          : send("POST", "/v1/subscriptions/checkout", body, "application/json",
              auth(account, "Idempotency-Key", key.toString()));
      return new HttpResult(r.status(), r.headers(), r.body());
    }

    @Override
    protected String body(int n) {
      return checkoutBody(n % 2 == 1 ? "PREMIUM" : "VIP", "pm_mock_success", "KA");
    }

    @Override
    protected long businessRows(UUID account) {
      return count("SELECT count(1) FROM billing.subscriptions WHERE account_id = ?", account);
    }

    @Override
    protected long outboxRows(UUID account) {
      return BillingIT.outboxRows(account, "billing.subscription.changed");
    }

    @Override
    protected void failNextMutation() {
      checkoutService.failNextCheckout();
    }
  }
}
