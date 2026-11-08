package in.dexter.commerce.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import in.dexter.commerce.app.AdAdminService;
import in.dexter.commerce.app.AdService;
import in.dexter.commerce.app.AdStore;
import in.dexter.commerce.app.ApplicationAdminService;
import in.dexter.commerce.app.ApplicationService;
import in.dexter.commerce.app.ClickLinks;
import in.dexter.commerce.app.ClickService;
import in.dexter.commerce.app.CounterBuffer;
import in.dexter.commerce.app.CounterFlusher;
import in.dexter.commerce.app.DealAdminService;
import in.dexter.commerce.app.DealService;
import in.dexter.commerce.app.PostbackService;
import in.dexter.commerce.app.ProjectionService;
import in.dexter.commerce.app.StatsService;
import in.dexter.commerce.domain.ClickToken;
import in.dexter.commerce.infra.AdRepository;
import in.dexter.commerce.infra.CommerceRepository;
import in.dexter.commerce.infra.RedisCounterBuffer;
import in.dexter.core.time.Clocks;
import in.dexter.db.page.KeysetCursorCodec;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.EnvelopeCodec;
import in.dexter.events.InboxGuard;
import in.dexter.events.OutboxWriter;
import in.dexter.idempotency.IdempotencyEngine;
import in.dexter.security.jwt.JwtVerifier;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires commerce (MASTER_PLAN 2.5). Two pools: the auto-configured one as commerce_app (users, the public, the
 * TRACKER and PROJECTOR actors) and {@link AdminPool} as commerce_admin with its own TenantTx, outbox writer and
 * repositories — not a DataSource bean, so Boot's single-DataSource auto-configuration stays as it is (ADR-0010).
 * TenantTx, KeysetPager, the cursor codec, IdempotencyEngine, OutboxWriter, InboxGuard and the JWT verifier come
 * from the platform libraries' auto-configuration.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CommerceProperties.class)
public class CommerceConfiguration {

  /** The commerce_admin pool with its own transaction manager, TenantTx, outbox writer and repositories. */
  public static final class AdminPool implements AutoCloseable {
    final HikariDataSource dataSource;
    final TenantTx tx;
    final OutboxWriter outbox;
    final CommerceRepository repo;
    final AdRepository ads;

    AdminPool(HikariDataSource dataSource, EnvelopeCodec codec, String schema, Clock clock, KeysetPager pager,
        JsonMapper mapper) {
      this.dataSource = dataSource;
      JdbcClient jdbc = JdbcClient.create(dataSource);
      this.tx = new TenantTx(new JdbcTransactionManager(dataSource), jdbc);
      this.outbox = new OutboxWriter(jdbc, codec, schema, schema, clock, () -> null);
      this.repo = new CommerceRepository(jdbc, pager, mapper);
      this.ads = new AdRepository(jdbc, pager);
    }

    @Override
    public void close() {
      dataSource.close();
    }
  }

  @Bean
  Clock commerceClock() {
    return Clocks.systemUtc();
  }

  @Bean(destroyMethod = "close")
  AdminPool commerceAdminPool(CommerceProperties p, Environment env, EnvelopeCodec codec, Clock clock,
      KeysetPager pager, JsonMapper mapper) {
    CommerceProperties.Admin a = p.admin();
    HikariConfig c = new HikariConfig();
    c.setPoolName("commerce-admin");
    c.setJdbcUrl(a.dbUrl() != null && !a.dbUrl().isBlank() ? a.dbUrl() : env.getRequiredProperty("spring.datasource.url"));
    c.setUsername(a.dbUsername());
    c.setPassword(a.dbPassword());
    c.setMaximumPoolSize(a.dbPoolSize());
    c.setMinimumIdle(0);
    c.setConnectionTimeout(Duration.ofSeconds(5).toMillis());
    c.setInitializationFailTimeout(-1); // start even when the DB is late
    return new AdminPool(new HikariDataSource(c), codec, env.getRequiredProperty("dexter.db.schema"), clock, pager,
        mapper);
  }

  /** The app pool's repository: deals, tracking, applications and projections as one bean. */
  @Bean
  CommerceRepository commerceRepository(JdbcClient jdbc, KeysetPager pager, JsonMapper mapper) {
    return new CommerceRepository(jdbc, pager, mapper);
  }

  @Bean
  AdStore adStore(JdbcClient jdbc, KeysetPager pager) {
    return new AdRepository(jdbc, pager);
  }

  @Bean
  CounterBuffer counterBuffer(StringRedisTemplate redis) {
    return new RedisCounterBuffer(redis);
  }

  @Bean
  ClickToken clickToken(CommerceProperties p) {
    String key = p.clicks().tokenKey();
    if (key == null || key.isBlank()) {
      throw new IllegalStateException("dexter.commerce.clicks.token-key (COMMERCE_CLICK_TOKEN_KEY) is required");
    }
    return new ClickToken(Base64.getDecoder().decode(key.strip()));
  }

  static String salt(CommerceProperties p) {
    String s = p.clicks().hashSalt();
    if (s == null || s.length() < 16) {
      throw new IllegalStateException("dexter.commerce.clicks.hash-salt (COMMERCE_HASH_SALT) needs >= 16 chars");
    }
    return s;
  }

  @Bean
  ClickLinks clickLinks(ClickToken tokens, CommerceProperties p, Clock clock) {
    return new ClickLinks(tokens, p.clicks().linkTtl(), clock);
  }

  @Bean
  WebMvcConfigurer commerceViewerResolver(JwtVerifier verifier, CommerceProperties p) {
    ViewerResolver resolver = new ViewerResolver(verifier, salt(p));
    return new WebMvcConfigurer() {
      @Override
      public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(resolver);
      }
    };
  }

  @Bean
  DealService dealService(TenantTx tx, CommerceRepository repo, ClickLinks links, KeysetCursorCodec codec,
      Clock clock, CommerceProperties p) {
    return new DealService(tx, repo, links, codec, clock, p.feed().maxActive());
  }

  @Bean
  DealAdminService dealAdminService(AdminPool pool, Clock clock) {
    return new DealAdminService(pool.tx, pool.repo, clock);
  }

  @Bean
  ClickService clickService(TenantTx tx, CommerceRepository repo, AdStore ads, CounterBuffer counters,
      ClickToken tokens, CommerceProperties p, Clock clock) {
    return new ClickService(tx, repo, ads, repo, counters, tokens, salt(p), clock);
  }

  @Bean
  CounterFlusher counterFlusher(TenantTx tx, CommerceRepository repo, CounterBuffer counters) {
    return new CounterFlusher(tx, repo, counters);
  }

  @Bean
  CommerceJobs commerceJobs(CounterFlusher flusher, CommerceProperties p) {
    return new CommerceJobs(flusher, p.jobs());
  }

  @Bean
  PostbackService postbackService(TenantTx tx, CommerceRepository repo, CommerceProperties p, JsonMapper mapper,
      Clock clock) {
    Map<String, byte[]> secrets = new LinkedHashMap<>();
    p.affiliateSecrets().forEach((network, secret) -> {
      if (secret != null && !secret.isBlank()) {
        if (secret.length() < 16) {
          throw new IllegalStateException("affiliate secret for " + network + " needs >= 16 chars");
        }
        secrets.put(network, secret.getBytes(StandardCharsets.UTF_8));
      }
    });
    return new PostbackService(tx, repo, secrets, mapper, clock);
  }

  @Bean
  ApplicationService applicationService(TenantTx tx, CommerceRepository repo, IdempotencyEngine idempotency,
      OutboxWriter outbox, JsonMapper mapper, Clock clock) {
    return new ApplicationService(tx, repo, repo, idempotency, outbox, mapper, clock);
  }

  @Bean
  ApplicationAdminService applicationAdminService(AdminPool pool, Clock clock) {
    return new ApplicationAdminService(pool.tx, pool.repo, pool.outbox, clock);
  }

  @Bean
  AdService adService(TenantTx tx, AdStore ads, CommerceRepository repo, CounterBuffer counters, ClickLinks links,
      CommerceProperties p, Clock clock) {
    return new AdService(tx, ads, repo, repo, counters, links, salt(p), new Random(), clock);
  }

  @Bean
  AdAdminService adAdminService(AdminPool pool, Clock clock) {
    return new AdAdminService(pool.tx, pool.ads, clock);
  }

  @Bean
  StatsService statsService(AdminPool pool, Clock clock) {
    return new StatsService(pool.tx, pool.repo, pool.ads, clock);
  }

  @Bean
  ProjectionService projectionService(TenantTx tx, InboxGuard inbox, CommerceRepository repo, JsonMapper mapper) {
    return new ProjectionService(tx, inbox, repo, mapper);
  }
}
