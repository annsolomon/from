package in.dexter.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.dexter.billing.support.BillingIT;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * The named test (MASTER_PLAN P16): <b>only one active subscription can exist</b> per account - enforced by the
 * database (subscriptions_one_live), not only by a check in the code, so it holds against a concurrent race too.
 */
class OneActiveSubscriptionIT extends BillingIT {
  static final String PLAN = "0192f1e2-0000-7000-8000-00000000b002";

  @Test
  void theDatabaseRefusesASecondLiveSubscription() {
    UUID a = UUID.randomUUID();
    String insert = "INSERT INTO billing.subscriptions (id, account_id, plan_id, status, current_period_start,"
        + " current_period_end, provider, billing_state) VALUES (gen_random_uuid(), :a, CAST(:p AS uuid), :st,"
        + " now(), now() + interval '1 month', 'mock', 'KA')";
    ADMIN.sql(insert).param("a", a).param("p", PLAN).param("st", "active").update();
    for (String st : List.of("active", "trialing", "past_due", "incomplete")) {
      assertThatThrownBy(() -> ADMIN.sql(insert).param("a", a).param("p", PLAN).param("st", st).update())
          .as("a second live subscription (" + st + ")").hasMessageContaining("subscriptions_one_live");
    }
    // ended ones do not count
    ADMIN.sql(insert).param("a", a).param("p", PLAN).param("st", "canceled").update();
    ADMIN.sql(insert).param("a", a).param("p", PLAN).param("st", "expired").update();
    assertThat(count("SELECT count(1) FROM billing.subscriptions WHERE account_id = ? AND status = 'active'", a))
        .isEqualTo(1);
  }

  @Test
  void aSecondCheckoutIsRefusedWhileOneIsLive() {
    UUID a = UUID.randomUUID();
    subscribe(a);
    Res second = checkout(a, UUID.randomUUID(), "VIP", "pm_mock_success", "KA");
    assertThat(second.status()).isEqualTo(409);
    assertThat(second.code()).isEqualTo("SUBSCRIPTION_EXISTS");
    // a checkout still waiting on its payment is live too
    UUID b = UUID.randomUUID();
    assertThat(checkout(b, UUID.randomUUID(), "PREMIUM", "pm_mock_delayed", "KA").status()).isEqualTo(202);
    assertThat(checkout(b, UUID.randomUUID(), "PREMIUM", "pm_mock_success", "KA").code())
        .isEqualTo("SUBSCRIPTION_EXISTS");
  }

  @Test
  void tenConcurrentCheckoutsWithDifferentKeysStartExactlyOne() throws Exception {
    UUID a = UUID.randomUUID();
    int before = provider.chargesCreated();
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Res>> futures = new ArrayList<>();
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 10; i++) {
        futures.add(pool.submit(() -> {
          go.await();
          return checkout(a, UUID.randomUUID(), "PREMIUM", "pm_mock_success", "KA");
        }));
      }
      go.countDown();
      int created = 0;
      for (Future<Res> f : futures) {
        Res r = f.get();
        if (r.status() == 201) {
          created++;
        } else {
          assertThat(r.status()).as(r.toString()).isEqualTo(409);
          assertThat(r.code()).isEqualTo("SUBSCRIPTION_EXISTS");
        }
      }
      assertThat(created).isEqualTo(1);
    }
    assertThat(count("SELECT count(1) FROM billing.subscriptions WHERE account_id = ?", a)).isEqualTo(1);
    assertThat(count("SELECT count(1) FROM billing.payments WHERE account_id = ?", a)).isEqualTo(1);
    assertThat(provider.chargesCreated() - before).isEqualTo(1);
  }
}
