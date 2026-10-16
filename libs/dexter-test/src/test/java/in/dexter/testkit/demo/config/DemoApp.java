package in.dexter.testkit.demo.config;

import com.zaxxer.hikari.HikariDataSource;
import in.dexter.cache.CacheSettings;
import in.dexter.cache.DexterCache;
import in.dexter.cache.InvalidationConsumer;
import in.dexter.db.page.CursorKeyRing;
import in.dexter.db.page.KeysetCursorCodec;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.EnvelopeCodec;
import in.dexter.events.InboxGuard;
import in.dexter.events.OutboxWriter;
import in.dexter.idempotency.IdempotencyEngine;
import in.dexter.idempotency.web.IdempotencyKeyArgumentResolver;
import in.dexter.testkit.containers.ServiceDb;
import in.dexter.testkit.containers.TestPostgres;
import in.dexter.testkit.containers.TestRedis;
import in.dexter.testkit.contract.LoadGate;
import in.dexter.testkit.net.TcpProxy;
import in.dexter.testkit.demo.api.NoteController;
import in.dexter.testkit.demo.app.NoteQueries;
import in.dexter.testkit.demo.app.NoteService;
import in.dexter.testkit.demo.infra.NoteCacheInvalidation;
import in.dexter.testkit.demo.infra.NoteRepository;
import in.dexter.testkit.security.TestJwt;
import in.dexter.security.web.AuthContextArgumentResolver;
import in.dexter.security.web.SecurityInterceptor;
import in.dexter.web.error.ProblemDetailsAdvice;
import in.dexter.web.json.StrictJsonCustomizer;
import in.dexter.web.page.PageRequestArgumentResolver;
import in.dexter.web.request.RequestIdFilter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wiring of the demo module (config) — what a service's Spring configuration does, done by hand so
 * every IT in this JVM shares one instance. The app pool (30) exceeds the 20 parallel requests of
 * the idempotency contract. Redis is reached as the restricted ACL user {@code demo} (rules from
 * {@code infra/redis/acl.tmpl}) through a {@link TcpProxy}, so cache tests can cut Redis without stopping it.
 */
public final class DemoApp {
  private static DemoApp instance;

  public final ServiceDb db;
  public final HikariDataSource appPool;
  public final JdbcClient jdbc;
  public final TenantTx tx;
  public final JsonMapper mapper;
  public final EnvelopeCodec codec;
  public final IdempotencyEngine idempotency;
  public final OutboxWriter outbox;
  public final InboxGuard inbox;
  public final NoteRepository notes;
  public final NoteService service;
  public final MockMvc mvc;
  /** The demo's identity provider; the demo service's audience is {@link #AUDIENCE}. */
  public final TestJwt idp;
  public static final String AUDIENCE = "demo";
  public static final String REDIS_USER = "demo";
  static final String REDIS_PASSWORD = "demo-redis-pw";
  /** Between the demo's Redis client and the shared Redis container. */
  public final TcpProxy redisProxy;
  /** Connected as the ACL user {@code demo}: 200 ms timeout, commands rejected while disconnected. */
  public final StringRedisTemplate redis;
  public final DexterCache cache;
  public final NoteQueries queries;
  public final LoadProbe loadProbe = new LoadProbe();
  /** Path B handler (the ITs run it directly or behind an EventConsumer). */
  public final InvalidationConsumer cacheInvalidation;
  /** Superuser access for assertions and bulk seeding only (bypasses RLS). */
  public final JdbcClient admin;

  private DemoApp() {
    db = TestPostgres.db("demo");
    db.exec("owner", resource("/demo/V202609270001__demo_notes.sql"));
    appPool = db.pool("app", 30, "demo-app");
    jdbc = JdbcClient.create(appPool);
    tx = new TenantTx(new JdbcTransactionManager(appPool), jdbc);
    mapper = StrictJsonCustomizer.apply(JsonMapper.builder()).build();
    codec = new EnvelopeCodec(mapper);
    idempotency = new IdempotencyEngine(jdbc, mapper, "demo");
    outbox = new OutboxWriter(jdbc, codec, "demo", "demo", Clock.systemUTC(), () -> null);
    inbox = new InboxGuard(jdbc, "demo");
    KeysetPager pager = new KeysetPager(new KeysetCursorCodec(new CursorKeyRing("t1",
        Map.of("t1", "demo-cursor-key-0123456789abcdef-0123".getBytes(StandardCharsets.UTF_8)))));
    notes = new NoteRepository(jdbc, pager);
    TestRedis.applyServiceAcl(REDIS_USER, REDIS_PASSWORD);
    redisProxy = TcpProxy.to(TestRedis.host(), TestRedis.port());
    redis = new StringRedisTemplate(TestRedis.connectionFactory(redisProxy.host(), redisProxy.port(), REDIS_USER,
        REDIS_PASSWORD));
    cache = DexterCache.create(redis, REDIS_USER, CacheSettings.defaults().withBreakerOpen(BREAKER_OPEN),
        JsonMapper.builder().build());
    cacheInvalidation = new NoteCacheInvalidation(cache.invalidator());
    service = new NoteService(tx, idempotency, outbox, notes, mapper, Clock.systemUTC(), cache.invalidator());
    queries = new NoteQueries(tx, notes, cache.tenant(), loadProbe);
    idp = TestJwt.create();
    SecurityInterceptor security = new SecurityInterceptor(idp.verifier(AUDIENCE, Clock.systemUTC()),
        Clock.systemUTC(), Duration.ofMinutes(10), List.of("/_dev/**", "/error"));
    mvc = MockMvcBuilders.standaloneSetup(new NoteController(service, queries))
        .setControllerAdvice(new ProblemDetailsAdvice())
        .addInterceptors(security)
        .setCustomArgumentResolvers(new PageRequestArgumentResolver(), new IdempotencyKeyArgumentResolver(),
            new AuthContextArgumentResolver())
        .setMessageConverters(new StringHttpMessageConverter(StandardCharsets.UTF_8),
            new JacksonJsonHttpMessageConverter(StrictJsonCustomizer.apply(JsonMapper.builder())))
        .addFilters(new RequestIdFilter())
        .build();
    admin = JdbcClient.create(new SimpleDriverDataSource(new org.postgresql.Driver(), db.url(), "postgres", "postgres"));
  }

  /** {@code Authorization} header value for a user token of {@code account}. */
  public String bearer(UUID account) {
    return "Bearer " + idp.user(account).audience(AUDIENCE).sign();
  }

  /** Short breaker window so tests that heal Redis see the cache again quickly. */
  public static final Duration BREAKER_OPEN = Duration.ofMillis(200);

  /** Test seam inside every cache load of {@link NoteQueries}: counts, slows or pauses loads. */
  public static final class LoadProbe implements Runnable {
    public final AtomicLong loads = new AtomicLong();
    public volatile Duration slow = Duration.ZERO;
    private final AtomicReference<LoadGate> gate = new AtomicReference<>();

    public LoadGate pauseNext() {
      LoadGate g = new LoadGate();
      gate.set(g);
      return g;
    }

    @Override
    public void run() {
      loads.incrementAndGet();
      LoadGate g = gate.getAndSet(null);
      if (g != null) {
        g.loaded();
      }
      if (!slow.isZero()) {
        try {
          Thread.sleep(slow);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  public static synchronized DemoApp get() {
    if (instance == null) {
      instance = new DemoApp();
    }
    return instance;
  }

  static String resource(String path) {
    try (InputStream in = DemoApp.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("missing resource " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
