package in.dexter.events.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.events.InboxGuard;
import in.dexter.events.OutboxWriter;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

class DexterEventsAutoConfigurationTest {
  final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(DexterEventsAutoConfiguration.class))
      .withBean(JdbcClient.class, () -> Mockito.mock(JdbcClient.class))
      .withBean(JsonMapper.class, () -> JsonMapper.builder().build());

  @Test
  void inactiveByDefault() {
    runner.run(ctx -> {
      assertThat(ctx).doesNotHaveBean(OutboxWriter.class);
      assertThat(ctx).doesNotHaveBean(RelayLifecycle.class);
    });
  }

  @Test
  void writerAndInboxWithSchema() {
    runner.withPropertyValues("dexter.db.schema=demo").run(ctx -> {
      assertThat(ctx).hasSingleBean(OutboxWriter.class);
      assertThat(ctx).hasSingleBean(InboxGuard.class);
      assertThat(ctx).doesNotHaveBean(RelayLifecycle.class);
    });
  }
}
