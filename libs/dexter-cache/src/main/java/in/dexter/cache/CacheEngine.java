package in.dexter.cache;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The read path shared by {@link TenantCache} and {@link GlobalCache} (ADR-0004 §3).
 *
 * <ol>
 *   <li>One script round trip returns the generation and the value under it (plus its PTTL).</li>
 *   <li><b>Hit</b>: decode and return; with XFetch probability (rising as the TTL runs out, scaled by how long
 *       the load took) refresh in the background first.</li>
 *   <li><b>Miss</b>: callers in this JVM join one future (in-process single flight); its leader takes the Redis
 *       lock ({@code SET NX PX}), re-reads once, loads, writes under the generation read <em>before</em>
 *       loading, and releases the lock by token. A leader that loses the lock to another instance polls for
 *       the value for {@code lockWait} (150 ms) and then loads from the DB itself without writing.</li>
 *   <li><b>Any Redis error</b>: bypass to the loader; the breaker then skips Redis for {@code breakerOpen}
 *       (1 s) so an outage costs one timeout, not one per request.</li>
 * </ol>
 * The loader must open its own (read-only) TenantTx; calling the cache inside a transaction is rejected,
 * because a loader joining a write transaction could cache uncommitted data.
 */
final class CacheEngine {
  private static final Logger log = LoggerFactory.getLogger(CacheEngine.class);
  private static final Duration POLL = Duration.ofMillis(15);

  private final RedisCacheStore store;
  private final CacheKeys keys;
  private final JsonMapper mapper;
  private final CacheMetrics metrics;
  private final CacheSettings s;
  private final ConcurrentHashMap<String, CompletableFuture<CacheValue>> inflight = new ConcurrentHashMap<>();
  private final Set<String> refreshing = ConcurrentHashMap.newKeySet();
  private volatile long breakerOpenUntil = Long.MIN_VALUE;
  private volatile boolean breakerLogged;

  CacheEngine(RedisCacheStore store, CacheKeys keys, JsonMapper mapper, CacheMetrics metrics, CacheSettings settings) {
    this.store = store;
    this.keys = keys;
    this.mapper = mapper;
    this.metrics = metrics;
    this.s = settings;
  }

  CacheKeys keys() {
    return keys;
  }

  <T> Optional<T> find(CacheScope scope, @Nullable UUID account, String paramsHash, Class<T> type,
      Supplier<Optional<T>> loader) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Read the cache outside any transaction: the loader opens its own TenantTx"
          + " (a loader joining a write transaction could cache uncommitted data)");
    }
    if (breakerOpen()) {
      metrics.bypass.increment();
      return decode(load(loader), type);
    }
    String genKey = keys.genKey(scope, account);
    String prefix = keys.valuePrefix(scope, account);
    RedisCacheStore.Read r;
    try {
      r = store.read(genKey, prefix, paramsHash);
    } catch (RuntimeException e) {
      trip(e);
      metrics.bypass.increment();
      return decode(load(loader), type);
    }
    String valueKey = prefix + ":g" + r.gen() + ":" + paramsHash;
    CacheValue cached = CacheValue.decode(r.value());
    if (cached != null) {
      Optional<T> hit = tryDecode(cached, type, valueKey);
      if (hit != null) {
        metrics.hit.increment();
        if (refreshEarly(cached.loadMillis(), r.pttlMillis(), s.earlyRefreshBeta(), uniform())) {
          refreshInBackground(scope, valueKey, loader);
        }
        return hit;
      }
    }
    metrics.miss.increment();
    CacheValue loaded = singleFlight(valueKey, () -> leader(scope, genKey, prefix, paramsHash, valueKey, loader));
    return decode(loaded, type);
  }

  /**
   * XFetch (Vattani et al.): refresh early when {@code loadMillis * beta * -ln(u) >= pttl}, {@code u} uniform in
   * (0, 1]. The probability rises as the remaining TTL approaches the load time; it is 0 when beta is 0.
   */
  static boolean refreshEarly(long loadMillis, long pttlMillis, double beta, double u) {
    if (pttlMillis <= 0 || beta <= 0) {
      return false;
    }
    return Math.max(1, loadMillis) * beta * -Math.log(u) >= pttlMillis;
  }

  /** TTL with ±jitter, never below 1 s. */
  static Duration jittered(Duration ttl, double jitter, double u) {
    double factor = 1 - jitter + 2 * jitter * u;
    return Duration.ofMillis(Math.max(1000, Math.round(ttl.toMillis() * factor)));
  }

  private static double uniform() {
    return 1.0 - ThreadLocalRandom.current().nextDouble(); // (0, 1]
  }

  private CacheValue singleFlight(String valueKey, Supplier<CacheValue> leader) {
    CompletableFuture<CacheValue> mine = new CompletableFuture<>();
    CompletableFuture<CacheValue> existing = inflight.putIfAbsent(valueKey, mine);
    if (existing != null) {
      try {
        return existing.join();
      } catch (CompletionException e) {
        throw unwrap(e);
      }
    }
    try {
      CacheValue v = leader.get();
      mine.complete(v);
      return v;
    } catch (RuntimeException | Error e) {
      mine.completeExceptionally(e);
      throw e;
    } finally {
      inflight.remove(valueKey, mine);
    }
  }

  private static RuntimeException unwrap(CompletionException e) {
    Throwable c = e.getCause();
    if (c instanceof RuntimeException re) {
      return re;
    }
    if (c instanceof Error err) {
      throw err;
    }
    return e;
  }

  private <T> CacheValue leader(CacheScope scope, String genKey, String prefix, String hash, String valueKey,
      Supplier<Optional<T>> loader) {
    String lockKey = CacheKeys.lockKey(valueKey);
    String token = UUID.randomUUID().toString();
    boolean locked;
    try {
      locked = store.tryLock(lockKey, token, s.lockTtl());
    } catch (RuntimeException e) {
      trip(e);
      return load(loader);
    }
    if (!locked) {
      return waitForOtherInstance(genKey, prefix, hash, loader);
    }
    try {
      // double check: the value may have landed between our read and the lock (a just-finished leader)
      CacheValue again;
      try {
        again = CacheValue.decode(store.read(genKey, prefix, hash).value());
      } catch (RuntimeException e) {
        trip(e);
        again = null;
      }
      if (again != null) {
        return again;
      }
      CacheValue v = load(loader); // loader failures (including DB errors) propagate to every waiter
      write(scope, valueKey, v);   // a failed write is a Redis failure: tripped and ignored
      return v;
    } finally {
      try {
        store.unlock(lockKey, token);
      } catch (RuntimeException ignored) {
        // the lock expires by itself (lockTtl)
      }
    }
  }

  private <T> CacheValue waitForOtherInstance(String genKey, String prefix, String hash, Supplier<Optional<T>> loader) {
    long deadline = System.nanoTime() + s.lockWait().toNanos();
    while (System.nanoTime() < deadline) {
      try {
        Thread.sleep(POLL);
        CacheValue v = CacheValue.decode(store.read(genKey, prefix, hash).value());
        if (v != null) {
          return v;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (RuntimeException e) {
        trip(e);
        break;
      }
    }
    return load(loader);
  }

  private <T> void refreshInBackground(CacheScope scope, String valueKey, Supplier<Optional<T>> loader) {
    if (!refreshing.add(valueKey)) {
      return;
    }
    metrics.staleRefresh.increment();
    Thread.ofVirtual().name("cache-refresh").start(() -> {
      String lockKey = CacheKeys.lockKey(valueKey);
      String token = UUID.randomUUID().toString();
      boolean locked = false;
      try {
        locked = store.tryLock(lockKey, token, s.lockTtl());
        if (locked) {
          write(scope, valueKey, load(loader));
        }
      } catch (RuntimeException e) {
        log.debug("cache early refresh of {} failed: {}", valueKey, e.toString());
      } finally {
        if (locked) {
          try {
            store.unlock(lockKey, token);
          } catch (RuntimeException ignored) {
            // expires by itself
          }
        }
        refreshing.remove(valueKey);
      }
    });
  }

  private <T> CacheValue load(Supplier<Optional<T>> loader) {
    long t0 = System.nanoTime();
    Optional<T> o = loader.get();
    long ms = (System.nanoTime() - t0) / 1_000_000;
    metrics.loads.increment();
    if (o == null) {
      throw new IllegalStateException("cache loader returned null; return Optional.empty() for not found");
    }
    return new CacheValue(ms, o.isPresent() ? mapper.writeValueAsString(o.get()) : null);
  }

  private void write(CacheScope scope, String valueKey, CacheValue v) {
    if (v.json() != null && v.json().length() > s.maxValueBytes()) {
      log.warn("cache value for {} not stored: {} chars > {}", valueKey, v.json().length(), s.maxValueBytes());
      return;
    }
    Duration base = v.notFound() && s.negativeTtl().compareTo(scope.ttl()) < 0 ? s.negativeTtl() : scope.ttl();
    try {
      store.write(valueKey, v.encode(), jittered(base, s.jitter(), uniform()));
    } catch (RuntimeException e) {
      trip(e);
    }
  }

  private <T> @Nullable Optional<T> tryDecode(CacheValue v, Class<T> type, String valueKey) {
    try {
      return decode(v, type);
    } catch (JacksonException e) {
      log.warn("cache value {} does not decode as {} ({}); reloading", valueKey, type.getSimpleName(), e.toString());
      return null;
    }
  }

  private <T> Optional<T> decode(CacheValue v, Class<T> type) {
    return v.json() == null ? Optional.empty() : Optional.of(mapper.readValue(v.json(), type));
  }

  private boolean breakerOpen() {
    long until = breakerOpenUntil;
    if (until == Long.MIN_VALUE) {
      return false;
    }
    if (System.nanoTime() - until < 0) {
      return true;
    }
    breakerOpenUntil = Long.MIN_VALUE;
    breakerLogged = false;
    log.info("cache: retrying Redis after the breaker window");
    return false;
  }

  private void trip(RuntimeException e) {
    breakerOpenUntil = System.nanoTime() + s.breakerOpen().toNanos();
    if (!breakerLogged) {
      breakerLogged = true;
      log.warn("CACHE_BYPASS Redis error, reading from the database for {} ms: {}", s.breakerOpen().toMillis(),
          e.toString());
    }
  }
}
