package in.dexter.cache;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A named invalidation unit (ADR-0004 §2): a write bumps exactly the scopes it affects. Each service lists
 * its scopes in {@code config/CacheScopes.java}. {@code schemaVersion} is bumped in code when the cached
 * payload shape changes, so old entries simply stop being read.
 */
public record CacheScope(String name, Kind kind, Duration ttl, int schemaVersion) {
  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,30}");
  /** Longest TTL a scope may have (ADR-0004 §4 table tops out at 30 min; 1 h leaves headroom). */
  public static final Duration MAX_TTL = Duration.ofHours(1);

  public enum Kind { TENANT, GLOBAL }

  public CacheScope {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(ttl, "ttl");
    if (!NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("scope name must match " + NAME + ": " + name);
    }
    if (ttl.compareTo(Duration.ofSeconds(1)) < 0 || ttl.compareTo(MAX_TTL) > 0) {
      throw new IllegalArgumentException("scope ttl must be between 1s and " + MAX_TTL + ": " + ttl);
    }
    if (schemaVersion < 1 || schemaVersion > 999) {
      throw new IllegalArgumentException("schemaVersion must be 1..999");
    }
  }

  /** Per-account data; keys carry the account id as a Redis Cluster hash tag. */
  public static CacheScope tenant(String name, Duration ttl) {
    return new CacheScope(name, Kind.TENANT, ttl, 1);
  }

  /** Reference data shared by all accounts (catalog rules, categories). */
  public static CacheScope global(String name, Duration ttl) {
    return new CacheScope(name, Kind.GLOBAL, ttl, 1);
  }

  public CacheScope withSchema(int version) {
    return new CacheScope(name, kind, ttl, version);
  }

  public boolean isTenant() {
    return kind == Kind.TENANT;
  }
}
