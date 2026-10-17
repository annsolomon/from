package in.dexter.ledger.infra;

import in.dexter.cache.CacheScope;
import in.dexter.cache.GlobalCache;
import in.dexter.ledger.app.ReferenceCache;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@link ReferenceCache} on dexter-cache's global (generation-keyed) Redis cache (ADR-0004). Entries live at most
 * 1 h (aliases) / 10 min (autocomplete); a merchant data change bumps the scope (P10 ingestion; the seed loader
 * restarts nothing, so a re-seed is visible after the TTL). Redis down → the loader answers (dexter-cache breaker).
 */
public final class RedisReferenceCache implements ReferenceCache {
  public static final CacheScope MERCHANT_ALIAS = CacheScope.global("merchant-alias", Duration.ofHours(1));
  public static final CacheScope MERCHANT_AUTOCOMPLETE = CacheScope.global("merchant-ac", Duration.ofMinutes(10));

  private final GlobalCache cache;

  public RedisReferenceCache(GlobalCache cache) {
    this.cache = cache;
  }

  @Override
  public <T> T get(Scope scope, String key, Class<T> type, Supplier<T> loader) {
    CacheScope s = switch (scope) {
      case MERCHANT_ALIAS -> MERCHANT_ALIAS;
      case MERCHANT_AUTOCOMPLETE -> MERCHANT_AUTOCOMPLETE;
    };
    return cache.get(s, Map.of("k", key), type, loader);
  }
}
