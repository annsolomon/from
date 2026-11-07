package in.dexter.billing;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.billing.support.BillingIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The named Mode B test (MASTER_PLAN P16, 5.3): <b>kill after the provider call, before completion -> the sweeper
 * resolves it</b>. The crash hook throws exactly where a {@code kill -9} would stop the process: the provider has
 * charged, and phase 3 (decide + stored response) never ran. No webhook is delivered in these tests, so it is the
 * sweeper - asking the provider by Dexter's own reference - that finishes the attempt, not the provider's callback.
 */
class ModeBRecoveryIT extends BillingIT {

  static String keyState(UUID account, UUID key) {
    return one("SELECT state || ':' || coalesce(recovery_point, '-') FROM billing.idempotency_keys"
        + " WHERE account_id = ? AND idem_key = ?", account, key);
  }

  @Test
  void aCrashAfterTheProviderCallIsResolvedByTheSweeper() {
    UUID a = UUID.randomUUID();
    UUID key = UUID.randomUUID();
    int before = provider.chargesCreated();
    checkoutService.crashAfterNextCharge();
    Res crashed = checkout(a, key, "PREMIUM", "pm_mock_success", "KA");
    assertThat(crashed.status()).as(crashed.toString()).isEqualTo(500);
    // what a dead process leaves behind: charged at the provider, nothing decided here, the key still held
    assertThat(provider.chargesCreated() - before).isEqualTo(1);
    assertThat(keyState(a, key)).isEqualTo("in_progress:payment_created");
    assertThat(one("SELECT status FROM billing.payments WHERE account_id = ?", a)).isEqualTo("pending");
    assertThat(one("SELECT status FROM billing.subscriptions WHERE account_id = ?", a)).isEqualTo("incomplete");

    // inside the lease the sweeper leaves it alone (the attempt might still be alive)
    checkoutService.recoverStale(100);
    assertThat(keyState(a, key)).isEqualTo("in_progress:payment_created");
    assertThat(one("SELECT status FROM billing.payments WHERE account_id = ?", a)).isEqualTo("pending");

    expireLease(a, key);
    assertThat(checkoutService.recoverStale(100)).isGreaterThanOrEqualTo(1);
    assertThat(keyState(a, key)).isEqualTo("completed:payment_created");
    assertThat(one("SELECT status FROM billing.payments WHERE account_id = ?", a)).isEqualTo("succeeded");
    assertThat(get("/v1/subscriptions/me", a).json().get("status").asString()).isEqualTo("active");
    assertThat(count("SELECT count(1) FROM billing.invoices WHERE account_id = ?", a)).isEqualTo(1);
    assertThat(outboxRows(a, "billing.subscription.changed")).isEqualTo(1);
    assertThat(outboxRows(a, "billing.entitlements.changed")).isEqualTo(1);

    // the client's retry of the same key gets the response the sweeper stored, and nobody was charged twice
    Res retry = checkout(a, key, "PREMIUM", "pm_mock_success", "KA");
    assertThat(retry.status()).as(retry.toString()).isEqualTo(201);
    assertThat(retry.header("Idempotent-Replayed")).isEqualTo("true");
    assertThat(retry.json().get("outcome").asString()).isEqualTo("succeeded");
    assertThat(provider.chargesCreated() - before).isEqualTo(1);
    // and a second sweep changes nothing for it
    checkoutService.recoverStale(100);
    assertThat(keyState(a, key)).isEqualTo("completed:payment_created");
    assertThat(outboxRows(a, "billing.subscription.changed")).isEqualTo(1);
  }

  @Test
  void aCrashResolvedByTheClientsOwnRetryAlsoChargesOnce() {
    UUID a = UUID.randomUUID();
    UUID key = UUID.randomUUID();
    int before = provider.chargesCreated();
    checkoutService.crashAfterNextCharge();
    assertThat(checkout(a, key, "PREMIUM", "pm_mock_success", "KA").status()).isEqualTo(500);
    expireLease(a, key);
    // the retry takes the expired lease over, resumes from payment_created and asks the provider again with the
    // same key - which answers with the original charge
    Res retry = checkout(a, key, "PREMIUM", "pm_mock_success", "KA");
    assertThat(retry.status()).as(retry.toString()).isEqualTo(201);
    assertThat(retry.header("Idempotent-Replayed")).isNull();
    assertThat(provider.chargesCreated() - before).isEqualTo(1);
    assertThat(count("SELECT count(1) FROM billing.payments WHERE account_id = ?", a)).isEqualTo(1);
    assertThat(keyState(a, key)).isEqualTo("completed:payment_created");
  }

  @Test
  void anAttemptThatNeverReachedTheProviderClosesAsNotCompleted() {
    UUID a = UUID.randomUUID();
    UUID key = UUID.randomUUID();
    int before = provider.chargesCreated();
    provider.unavailable(true);
    try {
      assertThat(checkout(a, key, "PREMIUM", "pm_mock_success", "KA").status()).isEqualTo(503);
    } finally {
      provider.unavailable(false);
    }
    assertThat(provider.chargesCreated() - before).as("the provider never saw it").isZero();
    expireLease(a, key);
    assertThat(checkoutService.recoverStale(100)).isGreaterThanOrEqualTo(1);
    assertThat(one("SELECT status || ':' || failure_code FROM billing.payments WHERE account_id = ?", a))
        .isEqualTo("failed:not_submitted");
    assertThat(one("SELECT status FROM billing.subscriptions WHERE account_id = ?", a))
        .isEqualTo("incomplete_expired");
    Res replay = checkout(a, key, "PREMIUM", "pm_mock_success", "KA");
    assertThat(replay.status()).isEqualTo(402);
    assertThat(replay.json().get("code").asString()).isEqualTo("PAYMENT_NOT_COMPLETED");
    assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
    // nothing was charged and nothing is live, so a new attempt (a new key) goes through
    assertThat(checkout(a, UUID.randomUUID(), "PREMIUM", "pm_mock_success", "KA").status()).isEqualTo(201);
  }
}
