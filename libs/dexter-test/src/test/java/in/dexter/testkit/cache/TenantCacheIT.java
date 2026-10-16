package in.dexter.testkit.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import in.dexter.cache.CacheInvalidator;
import in.dexter.cache.CacheScope;
import in.dexter.cache.CacheSettings;
import in.dexter.cache.CacheUnavailableException;
import in.dexter.cache.DexterCache;
import in.dexter.cache.InvalidationConsumer;
import in.dexter.core.id.Ids;
import in.dexter.core.tenant.Tenant;
import in.dexter.events.EventEnvelope;
import in.dexter.testkit.containers.TestRedis;
import in.dexter.testkit.demo.config.DemoApp;
import in.dexter.testkit.net.TcpProxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/** dexter-cache against a real Redis, as the restricted ACL user {@code libtest} (ADR-0004 §3–4). */
class TenantCacheIT {
  static final String SVC = "libtest";
  static final String PW = "libtest-pw";
  static final CacheScope ITEMS = CacheScope.tenant("items", Duration.ofMinutes(5));
  static final CacheScope RULES = CacheScope.global("rules", Duration.ofMinutes(5));
  static StringRedisTemplate direct;
  static TcpProxy proxy;
  static StringRedisTemplate proxied;

  public record Item(String name, long paise) {}

  @BeforeAll
  static void setUp() {
    TestRedis.applyServiceAcl(SVC, PW);
    direct = template(TestRedis.host(), TestRedis.port());
    proxy = TcpProxy.to(TestRedis.host(), TestRedis.port());
    proxied = template(proxy.host(), proxy.port());
  }

  @AfterAll
  static void tearDown() {
    proxy.close();
  }

  /** The proxied client is shared by the outage tests: wait until it is connected again. */
  static void awaitProxied() {
    await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(50)).ignoreExceptions()
        .until(() -> "PONG".equals(proxied.execute((RedisCallback<String>) x -> x.ping())));
  }

  static StringRedisTemplate template(String host, int port) {
    return new StringRedisTemplate(TestRedis.connectionFactory(host, port, SVC, PW));
  }

  static DexterCache cache(StringRedisTemplate t, CacheSettings s) {
    return DexterCache.create(t, SVC, s, JsonMapper.builder().build());
  }

  static DexterCache cache() {
    return cache(direct, CacheSettings.defaults());
  }

  static Tenant fresh() {
    return Tenant.user(UUID.randomUUID());
  }

  @Test
  void missThenHitThenBumpReloads() {
    DexterCache c = cache();
    Tenant t = fresh();
    AtomicInteger loads = new AtomicInteger();
    var params = Map.of("month", "2026-09");
    for (int i = 0; i < 3; i++) {
      Item it = c.tenant().get(t, ITEMS, params, Item.class, () -> new Item("n" + loads.incrementAndGet(), 5));
      assertThat(it).isEqualTo(new Item("n1", 5));
    }
    assertThat(loads).hasValue(1);
    c.invalidator().bump(t.accountId(), List.of(ITEMS));
    assertThat(c.tenant().get(t, ITEMS, params, Item.class, () -> new Item("n" + loads.incrementAndGet(), 5)).name())
        .isEqualTo("n2");
    assertThat(c.metrics().snapshot().hit()).isEqualTo(2);
    assertThat(c.metrics().snapshot().miss()).isEqualTo(2);
    assertThat(c.metrics().snapshot().bypass()).as("the ACL allowed everything").isZero();
  }

  @Test
  void singleFlightHoldsAcrossInstances() throws Exception {
    // two "pods": separate DexterCache and Redis connections, 25 concurrent misses each
    List<DexterCache> pods = List.of(cache(template(TestRedis.host(), TestRedis.port()), CacheSettings.defaults()),
        cache(template(TestRedis.host(), TestRedis.port()), CacheSettings.defaults()));
    Tenant t = fresh();
    AtomicInteger loads = new AtomicInteger();
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Item>> results = new ArrayList<>();
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 50; i++) {
        DexterCache pod = pods.get(i % 2);
        results.add(pool.submit(() -> {
          go.await();
          return pod.tenant().get(t, ITEMS, Map.of("k", "hot"), Item.class, () -> {
            loads.incrementAndGet();
            sleep(50);
            return new Item("hot", 1);
          });
        }));
      }
      go.countDown();
      for (Future<Item> f : results) {
        assertThat(f.get()).isEqualTo(new Item("hot", 1));
      }
    }
    assertThat(loads).hasValue(1);
  }

  @Test
  void notFoundIsCachedAndOptionalStaysEmpty() {
    DexterCache c = cache();
    Tenant t = fresh();
    AtomicInteger loads = new AtomicInteger();
    for (int i = 0; i < 3; i++) {
      Optional<Item> r = c.tenant().find(t, ITEMS, Map.of("id", "missing"), Item.class, () -> {
        loads.incrementAndGet();
        return Optional.empty();
      });
      assertThat(r).isEmpty();
    }
    assertThat(loads).hasValue(1);
  }

  @Test
  void nearExpiryHitsRefreshInTheBackground() {
    DexterCache c = cache(direct, CacheSettings.defaults().withEarlyRefreshBeta(1e6));
    CacheScope shortLived = CacheScope.tenant("short", Duration.ofSeconds(2));
    Tenant t = fresh();
    AtomicInteger loads = new AtomicInteger();
    var params = Map.of("k", "v");
    assertThat(c.tenant().get(t, shortLived, params, Item.class, () -> new Item("v" + loads.incrementAndGet(), 0)).name())
        .isEqualTo("v1");
    // a hit returns the cached value at once and refreshes in the background
    assertThat(c.tenant().get(t, shortLived, params, Item.class, () -> new Item("v" + loads.incrementAndGet(), 0)).name())
        .isEqualTo("v1");
    await().atMost(Duration.ofSeconds(5)).until(() -> loads.get() >= 2);
    await().atMost(Duration.ofSeconds(5)).until(() -> c.tenant()
        .get(t, shortLived, params, Item.class, () -> new Item("v" + loads.incrementAndGet(), 0)).name().equals("v2"));
    assertThat(c.metrics().snapshot().staleRefresh()).isPositive();
  }

  @Test
  void readingInsideATransactionIsRejected() {
    DexterCache c = cache();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> c.tenant().get(fresh(), ITEMS, Map.of(), Item.class, () -> new Item("x", 0)))
          .isInstanceOf(IllegalStateException.class).hasMessageContaining("outside any transaction");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }

  @Test
  void afterCommitBumpsOnCommitOnlyAndNeedsATransaction() {
    DexterCache c = cache();
    DemoApp app = DemoApp.get();
    Tenant t = fresh();
    String gen = "gen:" + SVC + ":{" + t.accountId() + "}:items";
    assertThatThrownBy(() -> c.invalidator().afterCommit(t.accountId(), ITEMS))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> app.tx.writeVoid(t, () -> {
      c.invalidator().afterCommit(t.accountId(), ITEMS);
      throw new IllegalArgumentException("rollback");
    })).isInstanceOf(IllegalArgumentException.class);
    assertThat(TestRedis.admin().opsForValue().get(gen)).as("no bump on rollback").isNull();
    app.tx.writeVoid(t, () -> {
      c.invalidator().afterCommit(t.accountId(), ITEMS);
      assertThat(TestRedis.admin().opsForValue().get(gen)).as("not before commit").isNull();
    });
    assertThat(TestRedis.admin().opsForValue().get(gen)).isEqualTo("1");
    assertThat(TestRedis.admin().getExpire(gen)).as("gen keys expire (7 days)").isPositive();
  }

  @Test
  void oversizedValuesAreServedButNotStored() {
    CacheSettings small = new CacheSettings(Duration.ofSeconds(5), Duration.ofMillis(150), Duration.ofSeconds(30), 0.1,
        Duration.ofSeconds(1), Duration.ofDays(7), 1024, 1.0);
    DexterCache c = cache(direct, small);
    Tenant t = fresh();
    AtomicInteger loads = new AtomicInteger();
    String big = "x".repeat(2000);
    for (int i = 0; i < 2; i++) {
      assertThat(c.tenant().get(t, ITEMS, Map.of(), Item.class, () -> {
        loads.incrementAndGet();
        return new Item(big, 1);
      }).name()).hasSize(2000);
    }
    assertThat(loads).hasValue(2);
  }

  @Test
  void aCorruptEntryIsReloadedAndOverwritten() {
    DexterCache c = cache();
    Tenant t = fresh();
    var params = Map.of("k", "corrupt");
    TestRedis.admin().opsForValue().set(c.tenant().valueKey(t, ITEMS, params, 0), "not-a-cache-value");
    AtomicInteger loads = new AtomicInteger();
    for (int i = 0; i < 2; i++) {
      assertThat(c.tenant().get(t, ITEMS, params, Item.class, () -> new Item("ok" + loads.incrementAndGet(), 1)).name())
          .isEqualTo("ok1");
    }
    assertThat(loads).hasValue(1);
  }

  @Test
  void tenantsWithTheSameParamsGetDifferentEntries() {
    DexterCache c = cache();
    Tenant a = fresh();
    Tenant b = fresh();
    var params = Map.of("month", "2026-09");
    assertThat(c.tenant().get(a, ITEMS, params, Item.class, () -> new Item("a", 1)).name()).isEqualTo("a");
    assertThat(c.tenant().get(b, ITEMS, params, Item.class, () -> new Item("b", 2)).name()).isEqualTo("b");
    assertThat(c.tenant().get(a, ITEMS, params, Item.class, () -> new Item("never", 0)).name()).isEqualTo("a");
    assertThat(c.tenant().valueKey(a, ITEMS, params, 0)).contains("{" + a.accountId() + "}")
        .isNotEqualTo(c.tenant().valueKey(b, ITEMS, params, 0));
    // the same account acting with another role does not share the owner's entry
    Tenant support = new Tenant(a.accountId(), Tenant.SUPPORT, null);
    assertThat(c.tenant().get(support, ITEMS, params, Item.class, () -> new Item("support-view", 1)).name())
        .isEqualTo("support-view");
  }

  @Test
  void globalScopesHaveOneGenerationForEveryone() {
    DexterCache c = cache();
    AtomicInteger loads = new AtomicInteger();
    var params = Map.of("v", UUID.randomUUID().toString());
    for (int i = 0; i < 2; i++) {
      c.global().get(RULES, params, Item.class, () -> new Item("rules" + loads.incrementAndGet(), 0));
    }
    assertThat(loads).hasValue(1);
    c.invalidator().bumpGlobal(List.of(RULES));
    assertThat(c.global().get(RULES, params, Item.class, () -> new Item("rules" + loads.incrementAndGet(), 0)).name())
        .isEqualTo("rules2");
    assertThatThrownBy(() -> c.global().get(ITEMS, params, Item.class, () -> new Item("x", 0)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void redisDownBypassesOnceThenTheBreakerSkipsRedis() throws Exception {
    awaitProxied();
    DexterCache c = cache(proxied, CacheSettings.defaults().withBreakerOpen(Duration.ofSeconds(2)));
    Tenant t = fresh();
    AtomicInteger loads = new AtomicInteger();
    var params = Map.of("k", "outage");
    c.tenant().get(t, ITEMS, params, Item.class, () -> new Item("db" + loads.incrementAndGet(), 0));
    proxy.cut();
    try {
      assertThat(c.tenant().get(t, ITEMS, params, Item.class, () -> new Item("db" + loads.incrementAndGet(), 0)).name())
          .isEqualTo("db2");
      long t0 = System.nanoTime();
      for (int i = 0; i < 5; i++) {
        c.tenant().get(t, ITEMS, params, Item.class, () -> new Item("db" + loads.incrementAndGet(), 0));
      }
      assertThat((System.nanoTime() - t0) / 1_000_000).as("breaker open: no Redis round trips").isLessThan(150);
      assertThat(c.metrics().snapshot().bypass()).isEqualTo(6);
    } finally {
      proxy.restore();
    }
    awaitProxied();
    sleep(2100);
    int before = loads.get();
    assertThat(c.tenant().get(t, ITEMS, params, Item.class, () -> new Item("db" + loads.incrementAndGet(), 0)).name())
        .as("the entry written before the outage is served again").isEqualTo("db1");
    assertThat(loads).hasValue(before);
  }

  @Test
  void pathBConsumerBumpsAndThrowsWhileRedisIsDown() {
    awaitProxied();
    DexterCache c = cache(proxied, CacheSettings.defaults());
    CacheInvalidator inv = c.invalidator();
    InvalidationConsumer consumer = InvalidationConsumer.of(inv, Map.of("demo.note.renamed", List.of(ITEMS),
        "catalog.rules.published", List.of(RULES)));
    UUID account = UUID.randomUUID();
    String gen = "gen:" + SVC + ":{" + account + "}:items";
    consumer.handle(event("demo.note.renamed", account));
    consumer.handle(event("demo.note.unrelated", account));
    assertThat(TestRedis.admin().opsForValue().get(gen)).isEqualTo("1");
    consumer.handle(event("catalog.rules.published", null));
    assertThat(TestRedis.admin().opsForValue().get("gen:" + SVC + ":global:rules")).isNotNull();
    long failures = c.metrics().snapshot().invalidationFailures();
    proxy.cut();
    try {
      assertThatThrownBy(() -> consumer.handle(event("demo.note.renamed", account)))
          .isInstanceOf(CacheUnavailableException.class);
      assertThat(c.metrics().snapshot().invalidationFailures()).isEqualTo(failures + 1);
    } finally {
      proxy.restore();
      awaitProxied();
    }
  }

  @Test
  void purgeRemovesOnlyThatAccountsKeys() {
    DexterCache c = cache();
    Tenant a = fresh();
    Tenant b = fresh();
    for (Tenant t : List.of(a, b)) {
      c.tenant().get(t, ITEMS, Map.of("p", 1), Item.class, () -> new Item("x", 1));
      c.invalidator().bump(t.accountId(), List.of(ITEMS));
      c.tenant().get(t, ITEMS, Map.of("p", 2), Item.class, () -> new Item("y", 2));
    }
    assertThat(TestRedis.keys("*" + a.accountId() + "*")).hasSize(3);
    assertThat(c.invalidator().purgeAccount(a.accountId())).isEqualTo(3);
    assertThat(TestRedis.keys("*" + a.accountId() + "*")).isEmpty();
    assertThat(TestRedis.keys("*" + b.accountId() + "*")).hasSize(3);
  }

  static EventEnvelope event(String type, UUID account) {
    return new EventEnvelope(Ids.newId(), type, 1, "test", Instant.now(), account, UUID.randomUUID(), 1, null,
        JsonNodeFactory.instance.objectNode());
  }

  static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

}
