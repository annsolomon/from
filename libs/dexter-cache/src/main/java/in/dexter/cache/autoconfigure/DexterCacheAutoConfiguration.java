package in.dexter.cache.autoconfigure;

import in.dexter.cache.CacheInvalidator;
import in.dexter.cache.CacheMetrics;
import in.dexter.cache.DexterCache;
import in.dexter.cache.GlobalCache;
import in.dexter.cache.TenantCache;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cache beans when the service has a {@link StringRedisTemplate} (Boot's data-redis auto-configuration,
 * i.e. {@code spring-boot-starter-data-redis} plus {@code spring.data.redis.*} with the service's ACL user
 * and {@code spring.data.redis.timeout=200ms}). Cached values use a private {@link JsonMapper}, so the
 * service's HTTP JSON settings never change the cached shape.
 */
@AutoConfiguration(afterName = "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration")
@ConditionalOnClass(StringRedisTemplate.class)
@ConditionalOnBean(StringRedisTemplate.class)
@EnableConfigurationProperties(DexterCacheProperties.class)
public class DexterCacheAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  DexterCache dexterCache(StringRedisTemplate redis, DexterCacheProperties props, Environment env) {
    String service = props.service() != null ? props.service() : env.getProperty("spring.application.name");
    if (service == null || service.isBlank()) {
      throw new IllegalStateException("Set dexter.cache.service or spring.application.name");
    }
    return DexterCache.create(redis, service, props.settings(), JsonMapper.builder().build());
  }

  @Bean
  @ConditionalOnMissingBean
  TenantCache dexterTenantCache(DexterCache cache) {
    return cache.tenant();
  }

  @Bean
  @ConditionalOnMissingBean
  GlobalCache dexterGlobalCache(DexterCache cache) {
    return cache.global();
  }

  @Bean
  @ConditionalOnMissingBean
  CacheInvalidator dexterCacheInvalidator(DexterCache cache) {
    return cache.invalidator();
  }

  /** A MeterBinder bean: Boot's metrics auto-configuration binds it to the registry. */
  @Bean
  @ConditionalOnMissingBean
  CacheMetrics dexterCacheMetrics(DexterCache cache) {
    return cache.metrics();
  }
}
