package in.dexter.cache;

import java.util.Objects;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Everything one service needs, built from a {@link StringRedisTemplate} (whose connection must use a
 * 200 ms command timeout, MASTER_PLAN 8.2, and the service's own Redis ACL user). Spring services get these
 * as beans from {@code DexterCacheAutoConfiguration}; tests and the demo build them with {@link #create}.
 */
public final class DexterCache {
  private final CacheKeys keys;
  private final CacheMetrics metrics;
  private final TenantCache tenant;
  private final GlobalCache global;
  private final CacheInvalidator invalidator;

  private DexterCache(StringRedisTemplate redis, String service, CacheSettings settings, JsonMapper mapper) {
    this.keys = new CacheKeys(service);
    this.metrics = new CacheMetrics(service);
    RedisCacheStore store = new RedisCacheStore(redis);
    CacheEngine engine = new CacheEngine(store, keys, mapper, metrics, settings);
    this.tenant = new TenantCache(engine);
    this.global = new GlobalCache(engine);
    this.invalidator = new CacheInvalidator(store, keys, metrics, settings.genTtl());
  }

  public static DexterCache create(StringRedisTemplate redis, String service, CacheSettings settings,
      JsonMapper mapper) {
    return new DexterCache(Objects.requireNonNull(redis, "redis"), service, Objects.requireNonNull(settings),
        Objects.requireNonNull(mapper, "mapper"));
  }

  public CacheKeys keys() {
    return keys;
  }

  public CacheMetrics metrics() {
    return metrics;
  }

  public TenantCache tenant() {
    return tenant;
  }

  public GlobalCache global() {
    return global;
  }

  public CacheInvalidator invalidator() {
    return invalidator;
  }
}
