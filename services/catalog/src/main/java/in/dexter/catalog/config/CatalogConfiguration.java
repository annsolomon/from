package in.dexter.catalog.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import in.dexter.catalog.app.AdminCatalogStore;
import in.dexter.catalog.app.CatalogAdminService;
import in.dexter.catalog.app.CatalogQueryService;
import in.dexter.catalog.app.CatalogStore;
import in.dexter.catalog.app.IngestionAdminService;
import in.dexter.catalog.app.IngestionRunner;
import in.dexter.catalog.app.IngestionStore;
import in.dexter.catalog.app.PageFetcher;
import in.dexter.catalog.app.Promoter;
import in.dexter.catalog.app.ReviewService;
import in.dexter.catalog.app.SnapshotStore;
import in.dexter.catalog.domain.FetchPolicy;
import in.dexter.catalog.infra.AdminCatalogRepository;
import in.dexter.catalog.infra.CatalogRepository;
import in.dexter.catalog.infra.IngestionRepository;
import in.dexter.catalog.infra.S3SnapshotStore;
import in.dexter.catalog.infra.SafeFetcher;
import in.dexter.core.time.Clocks;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.EnvelopeCodec;
import in.dexter.events.OutboxWriter;
import in.dexter.objectstore.S3Objects;
import in.dexter.objectstore.SigV4;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires catalog (MASTER_PLAN 2.5). Two pools: the auto-configured one as catalog_app (public reads; Flyway runs as
 * catalog_owner) and {@link AdminPool} as catalog_admin with its own TenantTx and outbox writer — deliberately not a
 * DataSource bean, so Boot's single-DataSource auto-configuration stays as it is (identity's pattern, ADR-0010).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CatalogProperties.class)
public class CatalogConfiguration {

  /** The catalog_admin pool with its own transaction manager, TenantTx and outbox writer. */
  public static final class AdminPool implements AutoCloseable {
    final HikariDataSource dataSource;
    final JdbcClient jdbc;
    final TenantTx tx;
    final OutboxWriter outbox;

    AdminPool(HikariDataSource dataSource, EnvelopeCodec codec, String schema, Clock clock) {
      this.dataSource = dataSource;
      this.jdbc = JdbcClient.create(dataSource);
      this.tx = new TenantTx(new JdbcTransactionManager(dataSource), jdbc);
      this.outbox = new OutboxWriter(jdbc, codec, schema, schema, clock, () -> null);
    }

    @Override
    public void close() {
      dataSource.close();
    }
  }

  @Bean
  Clock catalogClock() {
    return Clocks.systemUtc();
  }

  @Bean(destroyMethod = "close")
  AdminPool catalogAdminPool(CatalogProperties p, Environment env, EnvelopeCodec codec, Clock clock) {
    CatalogProperties.Admin a = p.admin();
    HikariConfig c = new HikariConfig();
    c.setPoolName("catalog-admin");
    c.setJdbcUrl(a.dbUrl() != null && !a.dbUrl().isBlank() ? a.dbUrl() : env.getRequiredProperty("spring.datasource.url"));
    c.setUsername(a.dbUsername());
    c.setPassword(a.dbPassword());
    c.setMaximumPoolSize(a.dbPoolSize());
    c.setMinimumIdle(0);
    c.setConnectionTimeout(Duration.ofSeconds(5).toMillis());
    c.setInitializationFailTimeout(-1); // start even when the DB is late
    return new AdminPool(new HikariDataSource(c), codec, env.getRequiredProperty("dexter.db.schema"), clock);
  }

  @Bean
  CatalogStore catalogStore(JdbcClient jdbc, KeysetPager pager) {
    return new CatalogRepository(jdbc, pager);
  }

  @Bean
  AdminCatalogStore adminCatalogStore(AdminPool pool, KeysetPager pager, JsonMapper mapper) {
    return new AdminCatalogRepository(pool.jdbc, pager, mapper);
  }

  @Bean
  CatalogQueryService catalogQueryService(TenantTx tx, CatalogStore store, JsonMapper mapper, Clock clock) {
    return new CatalogQueryService(tx, store, mapper, clock);
  }

  /** The only writer of published data: editor publish (P09) and approved review items (P10). */
  @Bean
  Promoter promoter(AdminPool pool, AdminCatalogStore store, JsonMapper mapper, Clock clock) {
    return new Promoter(store, pool.outbox, mapper, clock);
  }

  @Bean
  CatalogAdminService catalogAdminService(AdminPool pool, AdminCatalogStore store, JsonMapper mapper,
      Promoter promoter) {
    return new CatalogAdminService(pool.tx, store, pool.outbox, mapper, promoter);
  }

  // ------------------------------------------------------------------ P10 ingestion

  @Bean
  FetchPolicy fetchPolicy(CatalogProperties p) {
    CatalogProperties.Ingestion i = p.ingestion();
    return new FetchPolicy(i.allowedHosts() == null ? List.of() : i.allowedHosts(),
        i.devHosts() == null ? List.of() : i.devHosts());
  }

  @Bean
  PageFetcher pageFetcher(FetchPolicy policy, CatalogProperties p) {
    CatalogProperties.Ingestion i = p.ingestion();
    return new SafeFetcher(policy, SafeFetcher.SYSTEM, i.maxBytes(), i.timeout(), i.maxRedirects());
  }

  @Bean
  SnapshotStore snapshotStore(CatalogProperties p, Clock clock) {
    CatalogProperties.Snapshots s = p.ingestion().snapshots();
    return new S3SnapshotStore(new S3Objects(new SigV4(s.accessKey(), s.secretKey() == null ? "" : s.secretKey(),
        s.region()), s.endpoint(), null, s.bucket(), clock));
  }

  @Bean
  IngestionStore ingestionStore(AdminPool pool, KeysetPager pager) {
    return new IngestionRepository(pool.jdbc, pager);
  }

  @Bean
  IngestionAdminService ingestionAdminService(AdminPool pool, IngestionStore store, AdminCatalogStore catalog,
      FetchPolicy policy, JsonMapper mapper, Clock clock) {
    return new IngestionAdminService(pool.tx, store, catalog, policy, mapper, clock);
  }

  @Bean
  IngestionRunner ingestionRunner(AdminPool pool, IngestionStore store, AdminCatalogStore catalog, Promoter promoter,
      PageFetcher fetcher, SnapshotStore snapshots, JsonMapper mapper, Clock clock, CatalogProperties p) {
    CatalogProperties.Ingestion i = p.ingestion();
    return new IngestionRunner(pool.tx, store, catalog, promoter, fetcher, snapshots, mapper, clock,
        new IngestionRunner.Settings(i.politeness(), i.staleAfter(), i.maxAttempts(), i.sitemapMaxUrls()), null);
  }

  @Bean
  ReviewService reviewService(AdminPool pool, IngestionStore store, AdminCatalogStore catalog, Promoter promoter,
      JsonMapper mapper) {
    return new ReviewService(pool.tx, store, catalog, promoter, mapper);
  }

  @Bean
  IngestionWorker ingestionWorker(IngestionRunner runner, CatalogProperties p) {
    CatalogProperties.Ingestion i = p.ingestion();
    return new IngestionWorker(runner, i.workerEnabled(), i.pollInterval(), i.scheduleInterval());
  }
}
