package in.dexter.cache;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Key scheme (ADR-0004 §2), for one service:
 * <pre>
 * gen   : gen:{svc}:{&lt;account&gt;}:&lt;scope&gt;                 global: gen:{svc}:global:&lt;scope&gt;
 * value : {svc}:v&lt;schema&gt;:{&lt;account&gt;}:&lt;scope&gt;:g&lt;gen&gt;:&lt;hash&gt;   global: {svc}:v1:global:&lt;scope&gt;:g&lt;gen&gt;:&lt;hash&gt;
 * lock  : lock:&lt;value key&gt;
 * </pre>
 * Braces around the account id are a Redis Cluster hash tag: an account's gen counters, values and locks
 * share one slot, so the read script is single-slot. Keys hold only UUIDs and hashes, never raw PII.
 * The Redis ACL of the service ({@code infra/redis/acl.tmpl}) allows exactly these prefixes.
 */
public final class CacheKeys {
  private static final Pattern SERVICE = Pattern.compile("[a-z][a-z0-9_]{1,30}");
  private final String service;

  public CacheKeys(String service) {
    Objects.requireNonNull(service, "service");
    if (!SERVICE.matcher(service).matches()) {
      throw new IllegalArgumentException("service name must match " + SERVICE + ": " + service);
    }
    this.service = service;
  }

  public String service() {
    return service;
  }

  /** The account segment: {@code {uuid}} for tenant scopes, {@code global} for global ones. */
  static String owner(CacheScope scope, @Nullable UUID account) {
    if (scope.isTenant()) {
      Objects.requireNonNull(account, "tenant scope needs an account");
      return "{" + account + "}";
    }
    if (account != null) {
      throw new IllegalArgumentException("global scope " + scope.name() + " takes no account");
    }
    return "global";
  }

  public String genKey(CacheScope scope, @Nullable UUID account) {
    return "gen:" + service + ":" + owner(scope, account) + ":" + scope.name();
  }

  /** Value key up to (excluding) {@code :g<gen>:<hash>}. */
  public String valuePrefix(CacheScope scope, @Nullable UUID account) {
    return service + ":v" + scope.schemaVersion() + ":" + owner(scope, account) + ":" + scope.name();
  }

  public String valueKey(CacheScope scope, @Nullable UUID account, long gen, String paramsHash) {
    return valuePrefix(scope, account) + ":g" + gen + ":" + paramsHash;
  }

  public static String lockKey(String valueKey) {
    return "lock:" + valueKey;
  }

  /** SCAN patterns covering every key of {@code account} in this service's key space (privacy purge). */
  public String[] accountPatterns(UUID account) {
    String tag = "{" + account + "}";
    return new String[] {
        service + ":v*:" + tag + ":*",
        "lock:" + service + ":v*:" + tag + ":*",
        "gen:" + service + ":" + tag + ":*"};
  }

  /**
   * First 16 hex characters of the SHA-256 of the canonical parameters: keys sorted, each value
   * URL-encoded, nulls written as a bare key ({@code a&b=1}). Same parameters in any order → same hash.
   */
  public static String paramsHash(Map<String, ?> params) {
    return sha256Hex16(canonical(params));
  }

  static String canonical(Map<String, ?> params) {
    Objects.requireNonNull(params, "params");
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, ?> e : new TreeMap<String, Object>(params).entrySet()) {
      if (!sb.isEmpty()) {
        sb.append('&');
      }
      sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
      if (e.getValue() != null) {
        sb.append('=').append(URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8));
      }
    }
    return sb.toString();
  }

  static String sha256Hex16(String s) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(d, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
