package in.dexter.cache;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Read-through cache for reference data shared by all accounts (catalog rules, categories; ADR-0004 §5).
 * One generation per scope, bumped when the publishing event is applied. No in-process L1 yet (ADR-0004 §5
 * allows one for global data only; P09 adds it if the catalog needs it).
 */
public final class GlobalCache {
  private final CacheEngine engine;

  GlobalCache(CacheEngine engine) {
    this.engine = engine;
  }

  public <T> Optional<T> find(CacheScope scope, Map<String, ?> params, Class<T> type, Supplier<Optional<T>> loader) {
    requireGlobal(scope);
    return engine.find(scope, null, CacheKeys.paramsHash(params), type, loader);
  }

  public <T> T get(CacheScope scope, Map<String, ?> params, Class<T> type, Supplier<T> loader) {
    return find(scope, params, type, () -> Optional.of(Objects.requireNonNull(loader.get(),
        "loader returned null; use find() for optional values")))
        .orElseThrow(() -> new IllegalStateException("NOT_FOUND cached for a get() key: " + scope.name() + params));
  }

  private static void requireGlobal(CacheScope scope) {
    if (scope.isTenant()) {
      throw new IllegalArgumentException("scope " + scope.name() + " is per-tenant; use TenantCache");
    }
  }
}
