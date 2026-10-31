package in.dexter.reco.app;

import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The debounced recompute (MASTER_PLAN P14):
 *
 * <ol>
 *   <li>an event marks the account {@code dirty} with {@code dirty_since} set once, on the first event of the
 *       burst;</li>
 *   <li>this sweeper claims accounts dirty for longer than the debounce (default 3s) with {@code SKIP LOCKED},
 *       {@code batch} at a time;</li>
 *   <li>it recomputes each one and {@link RecommendationService#recompute} stores the result and publishes
 *       {@code reco.recommendation.updated} only if the state hash moved.</li>
 * </ol>
 *
 * <p>So 50 expense events three seconds apart from each other become one recompute, not fifty: the second event of
 * a burst finds the row already dirty and leaves {@code dirty_since} alone, and the sweeper does not claim the
 * account until the burst has been quiet for the debounce. {@code SKIP LOCKED} is what makes it safe to run on
 * every instance at once — two sweepers never claim the same account.
 *
 * <p>A recompute that throws leaves the account dirty with {@code attempts} raised, so a poisoned account is
 * retried rather than lost, and the log says which one it is.
 */
public final class Sweeper {
  private static final Logger log = LoggerFactory.getLogger(Sweeper.class);
  /** The owner tenant: claiming across accounts is cross-tenant by design, like portfolio's statement closer. */
  static final Tenant OWNER = new Tenant(new UUID(0, 0x5_1EE9L), "SWEEPER", null);

  /** What one pass did. */
  public record Pass(int claimed, int recomputed, int changed, int failed) {}

  private final TenantTx tx;
  private final RecoStore store;
  private final RecommendationService recommendations;
  private final Clock clock;
  private final Duration debounce;
  private final int batch;

  public Sweeper(TenantTx tx, RecoStore store, RecommendationService recommendations, Clock clock,
      Duration debounce, int batch) {
    this.tx = tx;
    this.store = store;
    this.recommendations = recommendations;
    this.clock = clock;
    this.debounce = debounce;
    this.batch = batch;
  }

  /** One pass: claim, then recompute each claimed account in its own transaction. */
  public Pass sweep() {
    Instant olderThan = clock.instant().minus(debounce);
    List<UUID> claimed = tx.write(OWNER, () -> store.claimDirty(olderThan, batch));
    int changed = 0;
    int failed = 0;
    for (UUID account : claimed) {
      try {
        // each account commits on its own: one bad account does not roll back the batch
        if (recommendations.recompute(account).changed()) {
          changed++;
        }
      } catch (RuntimeException e) {
        failed++;
        tx.writeVoid(OWNER, () -> store.markAttempt(account));
        log.error("RECO_RECOMPUTE_FAILED account={} error={}", account, e.toString());
      }
    }
    if (!claimed.isEmpty()) {
      log.info("RECO_SWEEP claimed={} changed={} failed={}", claimed.size(), changed, failed);
    }
    return new Pass(claimed.size(), claimed.size() - failed, changed, failed);
  }
}
