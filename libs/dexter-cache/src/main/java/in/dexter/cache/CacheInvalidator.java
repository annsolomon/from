package in.dexter.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Generation bumps (ADR-0004 §4).
 * <ul>
 *   <li><b>Path A</b> {@link #afterCommit}: called inside the write transaction (app layer); the bump runs after
 *       commit, never before and never inside it. A failure is logged and counted, never thrown — the commit
 *       already happened, and Path B / the TTL bound the staleness.</li>
 *   <li><b>Path B</b> {@link #bump}: called by {@link InvalidationConsumer}s for outbox events. Throws
 *       {@link CacheUnavailableException} so the event goes to the retry topics instead of being lost.</li>
 *   <li><b>Privacy</b> {@link #purgeAccount}: removes every key of an account in this service's key space.</li>
 * </ul>
 */
public final class CacheInvalidator {
  private static final Logger log = LoggerFactory.getLogger(CacheInvalidator.class);
  private final RedisCacheStore store;
  private final CacheKeys keys;
  private final CacheMetrics metrics;
  private final Duration genTtl;

  CacheInvalidator(RedisCacheStore store, CacheKeys keys, CacheMetrics metrics, Duration genTtl) {
    this.store = store;
    this.keys = keys;
    this.metrics = metrics;
    this.genTtl = genTtl;
  }

  /** Path A for per-account scopes. Must run inside the read-write transaction that changes the data. */
  public void afterCommit(UUID account, CacheScope... scopes) {
    Objects.requireNonNull(account, "account");
    registerAfterCommit(genKeys(account, List.of(scopes), true));
  }

  /** Path A for global scopes. */
  public void afterCommitGlobal(CacheScope... scopes) {
    registerAfterCommit(genKeys(null, List.of(scopes), false));
  }

  /** Path B: bump now; throws {@link CacheUnavailableException} when Redis does not take it. */
  public void bump(UUID account, Collection<CacheScope> scopes) {
    Objects.requireNonNull(account, "account");
    bumpNow(genKeys(account, scopes, true));
  }

  public void bumpGlobal(Collection<CacheScope> scopes) {
    bumpNow(genKeys(null, scopes, false));
  }

  /**
   * Privacy delete (ADR-0004 §7): UNLINK every value, lock and generation key of the account in this service's
   * key space (SCAN MATCH, COUNT 500). Returns the number of keys removed; throws when Redis fails, so the
   * saga step is retried.
   */
  public long purgeAccount(UUID account) {
    Objects.requireNonNull(account, "account");
    try {
      return store.unlinkMatching(keys.accountPatterns(account));
    } catch (RuntimeException e) {
      throw new CacheUnavailableException("cache purge of account failed", e);
    }
  }

  private List<String> genKeys(@Nullable UUID account, Collection<CacheScope> scopes, boolean tenant) {
    if (scopes.isEmpty()) {
      throw new IllegalArgumentException("at least one scope");
    }
    List<String> out = new ArrayList<>(scopes.size());
    for (CacheScope s : scopes) {
      if (s.isTenant() != tenant) {
        throw new IllegalArgumentException("scope " + s.name() + " is " + s.kind() + "; use the "
            + (s.isTenant() ? "per-account" : "global") + " method");
      }
      out.add(keys.genKey(s, account));
    }
    return out;
  }

  private void registerAfterCommit(List<String> genKeys) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("CacheInvalidator.afterCommit must run inside the read-write transaction"
          + " that changes the data (TenantTx.write)");
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        try {
          store.bump(genKeys, genTtl);
        } catch (RuntimeException e) {
          metrics.invalidationFailures.increment();
          log.warn("CACHE_INVALIDATION_FAILED path=A keys={} error={} (Path B / TTL will bound staleness)",
              genKeys.size(), e.toString());
        }
      }
    });
  }

  private void bumpNow(List<String> genKeys) {
    try {
      store.bump(genKeys, genTtl);
    } catch (RuntimeException e) {
      metrics.invalidationFailures.increment();
      throw new CacheUnavailableException("cache generation bump failed", e);
    }
  }
}
