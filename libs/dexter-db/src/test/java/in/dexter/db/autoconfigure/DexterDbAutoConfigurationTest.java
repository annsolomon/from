package in.dexter.db.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.db.page.KeysetCursorCodec;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.tenant.TenantTx;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DexterDbAutoConfigurationTest {
  final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(DexterDbAutoConfiguration.class));

  @Test
  void inactiveWithoutDataSourceOrKeys() {
    runner.run(ctx -> {
      assertThat(ctx).doesNotHaveBean(TenantTx.class);
      assertThat(ctx).doesNotHaveBean(KeysetCursorCodec.class);
    });
  }

  @Test
  void tenantTxWithDataSource() {
    runner.withBean(DataSource.class, () -> Mockito.mock(DataSource.class))
        .run(ctx -> assertThat(ctx).hasSingleBean(TenantTx.class));
  }

  @Test
  void cursorBeansWithKeys() {
    runner.withPropertyValues("dexter.cursor.active-kid=c1",
            "dexter.cursor.keys.c1=Y3Vyc29yLWtleS0wMTIzNDU2Nzg5YWJjZGVmMDEyMzQ1Njc4OWFi")
        .run(ctx -> {
          assertThat(ctx).hasSingleBean(KeysetCursorCodec.class);
          assertThat(ctx).hasSingleBean(KeysetPager.class);
        });
  }
}
