package in.dexter.ledger.app;

import java.util.function.Supplier;

/**
 * Cache for global reference lookups (MASTER_PLAN P08: "an LRU cache of resolved aliases in Redis"; autocomplete
 * results). Values are never account-specific: rules are applied after the alias cache. Misses are cached too (the
 * loader's answer is the value).
 */
public interface ReferenceCache {
  enum Scope { MERCHANT_ALIAS, MERCHANT_AUTOCOMPLETE }

  <T> T get(Scope scope, String key, Class<T> type, Supplier<T> loader);

  /** No cache (unit tests). */
  ReferenceCache NONE = new ReferenceCache() {
    @Override
    public <T> T get(Scope scope, String key, Class<T> type, Supplier<T> loader) {
      return loader.get();
    }
  };
}
