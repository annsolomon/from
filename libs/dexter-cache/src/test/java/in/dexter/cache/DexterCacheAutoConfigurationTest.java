package in.dexter.cache;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.cache.autoconfigure.DexterCacheAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** No connection is opened: the factory is never started, the beans are only built. */
class DexterCacheAutoConfigurationTest {
  final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(DexterCacheAutoConfiguration.class));

  @Test
  void nothingWithoutARedisTemplate() {
    runner.run(ctx -> assertThat(ctx).doesNotHaveBean(DexterCache.class).doesNotHaveBean(TenantCache.class));
  }

  @Test
  void allBeansWithATemplateNamedAfterTheApplication() {
    runner.withBean(StringRedisTemplate.class, () -> new StringRedisTemplate(new LettuceConnectionFactory()))
        .withPropertyValues("spring.application.name=ledger", "dexter.cache.lock-wait=100ms")
        .run(ctx -> {
          assertThat(ctx).hasSingleBean(TenantCache.class).hasSingleBean(GlobalCache.class)
              .hasSingleBean(CacheInvalidator.class).hasSingleBean(CacheMetrics.class);
          assertThat(ctx.getBean(DexterCache.class).keys().service()).isEqualTo("ledger");
        });
  }

  @Test
  void explicitServiceNameWins() {
    runner.withBean(StringRedisTemplate.class, () -> new StringRedisTemplate(new LettuceConnectionFactory()))
        .withPropertyValues("spring.application.name=ledger", "dexter.cache.service=reco")
        .run(ctx -> assertThat(ctx.getBean(DexterCache.class).keys().service()).isEqualTo("reco"));
  }

  @Test
  void failsWithoutAServiceName() {
    runner.withBean(StringRedisTemplate.class, () -> new StringRedisTemplate(new LettuceConnectionFactory()))
        .run(ctx -> assertThat(ctx).hasFailed());
  }
}
