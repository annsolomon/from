package in.dexter.catalog.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import in.dexter.catalog.app.AdminCatalogStore;
import in.dexter.catalog.app.CatalogAdminService;
import in.dexter.catalog.app.CatalogQueryService;
import in.dexter.catalog.app.CatalogStore;
import in.dexter.catalog.infra.AdminCatalogRepository;
import in.dexter.catalog.infra.CatalogRepository;
import in.dexter.core.time.Clocks;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.EnvelopeCodec;
import in.dexter.events.OutboxWriter;
import java.time.Clock;
import java.time.Duration;
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

  @Bean
  CatalogAdminService catalogAdminService(AdminPool pool, AdminCatalogStore store, JsonMapper mapper, Clock clock) {
    return new CatalogAdminService(pool.tx, store, pool.outbox, mapper, clock);
  }
}
