package in.dexter.testkit.contract;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.cache.CacheMetrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * ADR-0004 §10 — every service that caches extends this for each cached resource and supplies the hooks.
 * Reads and writes go through the service's real API and use-cases; Redis faults go through a proxy
 * ({@link in.dexter.testkit.net.TcpProxy}), so the Redis container is never stopped. Each test uses fresh
 * accounts. A test that cuts Redis restores it in {@code finally}.
 */
public abstract class CacheContract {

  /** Creates a resource with {@code title} through the service's write path (Path A runs). Returns its id. */
  protected abstract UUID create(UUID account, String title) throws Exception;

  /** Changes the title through the service's write path (Path A runs). */
  protected abstract void update(UUID account, UUID id, String title) throws Exception;

  /** The cached detail read through the API. */
  protected abstract HttpResult read(UUID account, UUID id) throws Exception;

  /** The cached list read through the API, with the same parameters for every account. */
  protected abstract HttpResult readList(UUID account) throws Exception;

  /** Loader invocations (DB loads) so far. */
  protected abstract long loads();

  protected abstract CacheMetrics metrics();

  /** Makes every load take at least {@code d} (single-flight case); {@code Duration.ZERO} resets. */
  protected abstract void slowLoads(Duration d);

  /** The next load pauses after its DB read until the gate is released. */
  protected abstract LoadGate pauseNextLoad();

  /** Partitions the service from Redis (connections reset, reconnects refused). */
  protected abstract void cutRedis();

  /** Heals the partition and returns once the service's Redis client works again and the breaker closed. */
  protected abstract void restoreRedis() throws Exception;

  /** Applies the account's outbox events to the service's InvalidationConsumer (Path B). */
  protected abstract void deliverPathB(UUID account) throws Exception;

  /** The service's privacy purge for the account; returns the number of keys removed. */
  protected abstract long purge(UUID account);

  /** Every Redis key that belongs to the account (SCAN as the admin user). */
  protected abstract Set<String> redisKeysOf(UUID account);

  private static void assertReads(HttpResult r, String title) {
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    assertThat(r.body()).contains("\"" + title + "\"");
  }

  @Test
  void writeThenReadReturnsTheNewValue() throws Exception {
    UUID a = UUID.randomUUID();
    UUID id = create(a, "v1");
    long hits = metrics().snapshot().hit();
    assertReads(read(a, id), "v1");
    long afterFirst = loads();
    assertReads(read(a, id), "v1");
    assertThat(loads()).as("second read is a hit").isEqualTo(afterFirst);
    assertThat(metrics().snapshot().hit()).isGreaterThan(hits);
    update(a, id, "v2");
    assertReads(read(a, id), "v2");
    assertThat(loads()).as("the write bumped the generation").isEqualTo(afterFirst + 1);
  }

  @Test
  void lostPathAIsRepairedByThePathBEvent() throws Exception {
    UUID a = UUID.randomUUID();
    UUID id = create(a, "v1");
    assertReads(read(a, id), "v1");
    long failures = metrics().snapshot().invalidationFailures();
    cutRedis();
    try {
      update(a, id, "v2"); // commits; the after-commit bump cannot reach Redis
    } finally {
      restoreRedis();
    }
    assertThat(metrics().snapshot().invalidationFailures()).isGreaterThan(failures);
    assertReads(read(a, id), "v1"); // stale: proves Path A really was lost
    deliverPathB(a);
    assertReads(read(a, id), "v2");
  }

  @Test
  void aReaderThatLoadedOldDataBeforeACommitNeverServesItAfterwards() throws Exception {
    UUID a = UUID.randomUUID();
    UUID id = create(a, "old");
    LoadGate gate = pauseNextLoad();
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<HttpResult> reader = pool.submit(() -> read(a, id)); // miss at generation g, loads "old"
      gate.awaitLoaded(Duration.ofSeconds(10));
      update(a, id, "new"); // commits and bumps to g+1
      gate.release();       // the reader now writes "old" under g
      assertReads(reader.get(10, TimeUnit.SECONDS), "old");
    }
    assertReads(read(a, id), "new");
    assertReads(read(a, id), "new");
  }

  @Test
  void redisDownBypassesToTheDatabase() throws Exception {
    UUID a = UUID.randomUUID();
    UUID id = create(a, "v1");
    assertReads(read(a, id), "v1");
    long bypass = metrics().snapshot().bypass();
    cutRedis();
    try {
      assertReads(read(a, id), "v1");
      update(a, id, "v2");
      assertReads(read(a, id), "v2"); // from the DB, never the stale entry
      assertThat(metrics().snapshot().bypass()).as("bypass counted").isGreaterThanOrEqualTo(bypass + 2);
    } finally {
      restoreRedis();
    }
    deliverPathB(a); // the outage lost Path A for v2; Path B repairs it
    assertReads(read(a, id), "v2");
  }

  @Test
  void tenantsNeverShareEntries() throws Exception {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID idA = create(a, "alpha-only");
    create(b, "bravo-only");
    HttpResult listA = readList(a);
    HttpResult listB = readList(b);
    assertReads(listA, "alpha-only");
    assertReads(listB, "bravo-only");
    assertThat(listA.body()).doesNotContain("bravo-only");
    assertThat(listB.body()).doesNotContain("alpha-only");
    assertThat(read(b, idA).status()).as("B reading A's id").isEqualTo(404);
    Set<String> keysA = redisKeysOf(a);
    Set<String> keysB = redisKeysOf(b);
    assertThat(keysA).isNotEmpty().allMatch(k -> k.contains(a.toString()) && !k.contains(b.toString()));
    assertThat(keysB).isNotEmpty().doesNotContainAnyElementsOf(keysA);
  }

  @Test
  void fiftyConcurrentMissesLoadOnce() throws Exception {
    UUID a = UUID.randomUUID();
    UUID id = create(a, "hot");
    slowLoads(Duration.ofMillis(100));
    try {
      long before = loads();
      CountDownLatch go = new CountDownLatch(1);
      List<Future<HttpResult>> results = new ArrayList<>();
      try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int i = 0; i < 50; i++) {
          results.add(pool.submit(() -> {
            go.await();
            return read(a, id);
          }));
        }
        go.countDown();
        for (Future<HttpResult> f : results) {
          assertReads(f.get(30, TimeUnit.SECONDS), "hot");
        }
      }
      assertThat(loads() - before).isEqualTo(1);
    } finally {
      slowLoads(Duration.ZERO);
    }
  }

  @Test
  void notFoundIsCachedBriefly() throws Exception {
    UUID a = UUID.randomUUID();
    create(a, "exists");
    UUID missing = UUID.randomUUID();
    assertThat(read(a, missing).status()).isEqualTo(404);
    long before = loads();
    assertThat(read(a, missing).status()).isEqualTo(404);
    assertThat(loads()).isEqualTo(before);
  }

  @Test
  void privacyPurgeLeavesNoKeyOfTheAccount() throws Exception {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID id = create(a, "to-forget");
    create(b, "keep");
    read(a, id);
    readList(a);
    readList(b);
    assertThat(redisKeysOf(a)).isNotEmpty();
    assertThat(purge(a)).isPositive();
    assertThat(redisKeysOf(a)).isEmpty();
    assertThat(redisKeysOf(b)).as("other accounts untouched").isNotEmpty();
    assertReads(read(a, id), "to-forget"); // still correct afterwards (DB)
  }
}
