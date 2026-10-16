package in.dexter.db.autoconfigure;

import in.dexter.db.page.CursorKeyRing;
import in.dexter.db.page.KeysetCursorCodec;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.tenant.TenantTx;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wires {@link TenantTx} when the service has a DataSource, and the cursor codec/pager when cursor
 * keys are configured. Inactive in services without a database (P01: all of them).
 */
@AutoConfiguration(afterName = {
    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
    "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration"})
@EnableConfigurationProperties(CursorProperties.class)
public class DexterDbAutoConfiguration {

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnBean(DataSource.class)
  static class TenantTxConfiguration {
    @Bean
    @ConditionalOnMissingBean
    TenantTx tenantTx(DataSource dataSource, ObjectProvider<PlatformTransactionManager> txManager,
        ObjectProvider<JdbcClient> jdbcClient) {
      PlatformTransactionManager tm = txManager.getIfAvailable(() -> new JdbcTransactionManager(dataSource));
      JdbcClient jdbc = jdbcClient.getIfAvailable(() -> JdbcClient.create(dataSource));
      return new TenantTx(tm, jdbc);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "dexter.cursor", name = "active-kid")
  static class CursorConfiguration {
    @Bean
    @ConditionalOnMissingBean
    KeysetCursorCodec keysetCursorCodec(CursorProperties props) {
      return new KeysetCursorCodec(CursorKeyRing.fromBase64(props.activeKid(), props.keys()));
    }

    @Bean
    @ConditionalOnMissingBean
    KeysetPager keysetPager(KeysetCursorCodec codec) {
      return new KeysetPager(codec);
    }
  }
}
