package in.dexter.testkit.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * MASTER_PLAN 5.5 — every mutating endpoint's test class extends this and supplies the hooks.
 * Each test uses fresh accounts, so the cases are independent.
 */
public abstract class IdempotencyContract {

  /** Performs the mutation for {@code account} with an optional key and a JSON body. */
  protected abstract HttpResult mutate(UUID account, UUID key, String body) throws Exception;

  /** A valid request body; different {@code n} → a different body. */
  protected abstract String body(int n);

  /** Business rows the mutation created for the account. */
  protected abstract long businessRows(UUID account) throws Exception;

  /** Outbox rows the mutation wrote for the account. */
  protected abstract long outboxRows(UUID account) throws Exception;

  /** Makes the next mutation fail with a 500 <em>after</em> its business write, inside the transaction. */
  protected abstract void failNextMutation();

  /** False for endpoints where the key is optional (case 5 is then skipped). */
  protected boolean keyRequired() {
    return true;
  }

  /**
   * False for a mutation that writes no event (P12: creating a statement upload; its event comes with
   * {@code :submit}); the outbox counts are then asserted to stay zero instead of one.
   */
  protected boolean writesEvent() {
    return true;
  }

  private long expectedEvents() {
    return writesEvent() ? 1 : 0;
  }

  @Test
  void sameKeyTwiceCreatesOneRowAndReplaysTheResponse() throws Exception {
    UUID a = UUID.randomUUID();
    UUID k = UUID.randomUUID();
    HttpResult first = mutate(a, k, body(1));
    HttpResult second = mutate(a, k, body(1));
    assertThat(first.is2xx()).as("first: " + first).isTrue();
    assertThat(first.header("Idempotent-Replayed")).isNull();
    assertThat(second.status()).isEqualTo(first.status());
    assertThat(second.body()).isEqualTo(first.body());
    assertThat(second.header("Idempotent-Replayed")).isEqualTo("true");
    assertThat(second.header("Location")).isEqualTo(first.header("Location"));
    assertThat(businessRows(a)).isEqualTo(1);
    assertThat(outboxRows(a)).isEqualTo(expectedEvents());
  }

  @Test
  void sameKeyDifferentBodyIs422() throws Exception {
    UUID a = UUID.randomUUID();
    UUID k = UUID.randomUUID();
    assertThat(mutate(a, k, body(1)).is2xx()).isTrue();
    HttpResult reused = mutate(a, k, body(2));
    assertThat(reused.status()).isEqualTo(422);
    assertThat(reused.body()).contains("IDEMPOTENCY_KEY_REUSED");
    assertThat(businessRows(a)).isEqualTo(1);
  }

  @Test
  void twentyParallelRequestsCreateExactlyOneRowAndOneEvent() throws Exception {
    UUID a = UUID.randomUUID();
    UUID k = UUID.randomUUID();
    int n = 20;
    CountDownLatch go = new CountDownLatch(1);
    List<Future<HttpResult>> futures = new ArrayList<>();
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < n; i++) {
        futures.add(pool.submit(() -> {
          go.await();
          return mutate(a, k, body(1));
        }));
      }
      go.countDown();
      Set<String> bodies = new HashSet<>();
      int ok = 0;
      for (Future<HttpResult> f : futures) {
        HttpResult r = f.get();
        if (r.is2xx()) {
          ok++;
          bodies.add(r.body());
        } else {
          assertThat(r.status()).as("non-2xx must be 409 IN_PROGRESS: " + r).isEqualTo(409);
          assertThat(r.body()).contains("IDEMPOTENCY_IN_PROGRESS");
          assertThat(r.header("Retry-After")).isEqualTo("1");
        }
      }
      assertThat(ok).isPositive();
      assertThat(bodies).hasSize(1);
    }
    assertThat(businessRows(a)).isEqualTo(1);
    assertThat(outboxRows(a)).isEqualTo(expectedEvents());
  }

  @Test
  void aFailedFirstAttemptLeavesNoKeySoTheRetrySucceeds() throws Exception {
    UUID a = UUID.randomUUID();
    UUID k = UUID.randomUUID();
    failNextMutation();
    HttpResult failed = mutate(a, k, body(1));
    assertThat(failed.status()).isEqualTo(500);
    assertThat(businessRows(a)).isZero();
    assertThat(outboxRows(a)).isZero();
    HttpResult retry = mutate(a, k, body(1));
    assertThat(retry.is2xx()).as("retry: " + retry).isTrue();
    assertThat(retry.header("Idempotent-Replayed")).isNull();
    assertThat(businessRows(a)).isEqualTo(1);
  }

  @Test
  void missingRequiredKeyIs400() throws Exception {
    if (!keyRequired()) {
      return;
    }
    UUID a = UUID.randomUUID();
    HttpResult r = mutate(a, null, body(1));
    assertThat(r.status()).isEqualTo(400);
    assertThat(r.body()).contains("IDEMPOTENCY_KEY_REQUIRED");
    assertThat(businessRows(a)).isZero();
  }

  @Test
  void keysAreTenantScoped() throws Exception {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID k = UUID.randomUUID();
    HttpResult ra = mutate(a, k, body(1));
    HttpResult rb = mutate(b, k, body(1));
    assertThat(ra.is2xx()).isTrue();
    assertThat(rb.is2xx()).isTrue();
    assertThat(rb.header("Idempotent-Replayed")).isNull();
    assertThat(businessRows(a)).isEqualTo(1);
    assertThat(businessRows(b)).isEqualTo(1);
  }
}
