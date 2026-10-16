package in.dexter.idempotency.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.idempotency.IdempotencyEngine;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

class DexterIdempotencyAutoConfigurationTest {
  final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(DexterIdempotencyAutoConfiguration.class));

  @Test
  void noEngineWithoutSchema() {
    runner.withBean(JdbcClient.class, () -> Mockito.mock(JdbcClient.class))
        .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
        .run(ctx -> assertThat(ctx).doesNotHaveBean(IdempotencyEngine.class));
  }

  @Test
  void engineWithSchemaAndJdbc() {
    runner.withBean(JdbcClient.class, () -> Mockito.mock(JdbcClient.class))
        .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
        .withPropertyValues("dexter.db.schema=demo")
        .run(ctx -> assertThat(ctx).hasSingleBean(IdempotencyEngine.class));
  }
}
