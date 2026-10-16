package in.dexter.cache;

import in.dexter.core.tenant.Tenant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Read-through cache for per-account data (ADR-0004). Use it in the {@code app} layer, outside any transaction;
 * the loader opens its own read-only {@code TenantTx}:
 * <pre>{@code
 * return cache.find(t, CacheScopes.NOTES, Map.of("id", id), NoteView.class,
 *     () -> tx.read(t, () -> repo.find(id).map(NoteView::of)));
 * }</pre>
 * The key carries the tenant's account id (hash tag) and the parameter hash includes the tenant's role, so an
 * admin acting on an account never shares an entry with the account owner.
 */
public final class TenantCache {
  static final String ROLE_PARAM = "@role";
  private final CacheEngine engine;

  TenantCache(CacheEngine engine) {
    this.engine = engine;
  }

  /** Optional result: an empty loader result is cached as NOT_FOUND for the negative TTL (30 s). */
  public <T> Optional<T> find(Tenant tenant, CacheScope scope, Map<String, ?> params, Class<T> type,
      Supplier<Optional<T>> loader) {
    Objects.requireNonNull(tenant, "tenant");
    requireTenantScope(scope);
    return engine.find(scope, tenant.accountId(), paramsHash(tenant, params), type, loader);
  }

  /** A value that always exists (lists, summaries). The loader must not return null. */
  public <T> T get(Tenant tenant, CacheScope scope, Map<String, ?> params, Class<T> type, Supplier<T> loader) {
    return find(tenant, scope, params, type, () -> Optional.of(Objects.requireNonNull(loader.get(),
        "loader returned null; use find() for optional values")))
        .orElseThrow(() -> new IllegalStateException("NOT_FOUND cached for a get() key: " + scope.name() + params));
  }

  /** The key a read would use right now for generation {@code gen} (diagnostics and tests). */
  public String valueKey(Tenant tenant, CacheScope scope, Map<String, ?> params, long gen) {
    requireTenantScope(scope);
    return engine.keys().valueKey(scope, tenant.accountId(), gen, paramsHash(tenant, params));
  }

  static String paramsHash(Tenant tenant, Map<String, ?> params) {
    Map<String, Object> p = new HashMap<>(params);
    if (p.containsKey(ROLE_PARAM)) {
      throw new IllegalArgumentException(ROLE_PARAM + " is reserved");
    }
    p.put(ROLE_PARAM, tenant.role());
    return CacheKeys.paramsHash(p);
  }

  private static void requireTenantScope(CacheScope scope) {
    if (!scope.isTenant()) {
      throw new IllegalArgumentException("scope " + scope.name() + " is global; use GlobalCache");
    }
  }
}
