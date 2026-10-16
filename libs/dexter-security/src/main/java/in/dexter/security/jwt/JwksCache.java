package in.dexter.security.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import in.dexter.security.jwt.TokenRejectedException.Reason;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifiers by {@code kid}, built from identity's JWKS (MASTER_PLAN 7.1).
 *
 * <ul>
 *   <li>Fresh for {@code ttl} (5 min); requests on a fresh snapshot with a known kid take no lock.</li>
 *   <li>An unknown kid triggers a refetch (key rotation), but fetches of any kind happen at most once
 *       per {@code minRefetchInterval}, so a flood of made-up kids or an identity outage cannot turn
 *       every request into a blocking HTTP call.</li>
 *   <li>If a refetch fails, the last good keys stay usable until {@code maxStale} after they were
 *       fetched; after that tokens are rejected with {@link Reason#JWKS_UNAVAILABLE} (503).</li>
 *   <li>Only EC P-256 keys with {@code use} absent or {@code sig} and {@code alg} absent or
 *       {@code ES256} are loaded. A kid that appears twice is dropped (ambiguous).</li>
 * </ul>
 */
public final class JwksCache {
  private static final Logger log = LoggerFactory.getLogger(JwksCache.class);

  private record Snapshot(Map<String, JWSVerifier> verifiers, Instant fetchedAt) {}

  private final JwksSource source;
  private final Clock clock;
  private final Duration ttl;
  private final Duration minRefetchInterval;
  private final Duration maxStale;
  private final ReentrantLock lock = new ReentrantLock();
  private final AtomicLong fetches = new AtomicLong();
  private volatile @Nullable Snapshot snapshot;
  private Instant lastAttempt = Instant.MIN; // guarded by lock

  public JwksCache(JwksSource source, Clock clock, Duration ttl, Duration minRefetchInterval, Duration maxStale) {
    this.source = Objects.requireNonNull(source, "source");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ttl = positive(ttl, "ttl");
    this.minRefetchInterval = positive(minRefetchInterval, "minRefetchInterval");
    this.maxStale = positive(maxStale, "maxStale");
    if (maxStale.compareTo(ttl) < 0) {
      throw new IllegalArgumentException("maxStale must be >= ttl");
    }
  }

  /** Defaults from MASTER_PLAN 7.1: 5 min TTL, refetch at most every 10 s, stale keys usable for 1 h. */
  public static JwksCache withDefaults(JwksSource source, Clock clock) {
    return new JwksCache(source, clock, Duration.ofMinutes(5), Duration.ofSeconds(10), Duration.ofHours(1));
  }

  /**
   * The verifier for {@code kid}.
   *
   * @throws TokenRejectedException {@code KID_UNKNOWN} or {@code JWKS_UNAVAILABLE}
   */
  public JWSVerifier verifier(String kid) {
    Snapshot s = snapshot;
    Instant now = clock.instant();
    if (s != null && isFresh(s, now)) {
      JWSVerifier v = s.verifiers().get(kid);
      if (v != null) {
        return v;
      }
      return refresh(kid, true);
    }
    if (s != null && isUsable(s, now) && s.verifiers().containsKey(kid) && lock.isLocked()) {
      return s.verifiers().get(kid); // someone is already refreshing; don't queue behind their HTTP call
    }
    return refresh(kid, false);
  }

  /** How many times the source was called (tests, diagnostics). */
  public long fetchCount() {
    return fetches.get();
  }

  private JWSVerifier refresh(String kid, boolean unknownKid) {
    lock.lock();
    try {
      Instant now = clock.instant();
      Snapshot cur = snapshot;
      if (cur != null && isFresh(cur, now) && cur.verifiers().containsKey(kid)) {
        return cur.verifiers().get(kid); // refreshed by another thread while we waited
      }
      boolean mayFetch = now.isAfter(lastAttempt.plus(minRefetchInterval)) || lastAttempt.equals(Instant.MIN);
      if (mayFetch) {
        lastAttempt = now;
        try {
          cur = load(now);
          snapshot = cur;
        } catch (IOException | RuntimeException e) {
          log.warn("JWKS_REFRESH_FAILED source={} error={}", source, e.toString());
        }
      }
      if (cur == null || !isUsable(cur, now)) {
        throw new TokenRejectedException(Reason.JWKS_UNAVAILABLE, "no usable signing keys");
      }
      JWSVerifier v = cur.verifiers().get(kid);
      if (v == null) {
        throw new TokenRejectedException(Reason.KID_UNKNOWN, unknownKid && !mayFetch
            ? "unknown kid (refetch rate-limited)" : "unknown kid");
      }
      return v;
    } finally {
      lock.unlock();
    }
  }

  private Snapshot load(Instant now) throws IOException {
    fetches.incrementAndGet();
    JWKSet set = source.fetch();
    Map<String, JWSVerifier> verifiers = new HashMap<>();
    Set<String> duplicates = new HashSet<>();
    for (JWK jwk : set.getKeys()) {
      if (!(jwk instanceof ECKey ec) || !Curve.P_256.equals(ec.getCurve()) || ec.getKeyID() == null) {
        continue;
      }
      if (ec.getKeyUse() != null && !KeyUse.SIGNATURE.equals(ec.getKeyUse())) {
        continue;
      }
      if (ec.getAlgorithm() != null && !JWSAlgorithm.ES256.getName().equals(ec.getAlgorithm().getName())) {
        continue;
      }
      String kid = ec.getKeyID();
      if (duplicates.contains(kid)) {
        continue;
      }
      if (verifiers.containsKey(kid)) {
        verifiers.remove(kid);
        duplicates.add(kid);
        log.warn("JWKS_DUPLICATE_KID kid={} (dropped)", kid);
        continue;
      }
      try {
        verifiers.put(kid, new ECDSAVerifier(ec.toPublicJWK()));
      } catch (JOSEException e) {
        log.warn("JWKS_KEY_SKIPPED kid={} error={}", kid, e.toString());
      }
    }
    if (verifiers.isEmpty()) {
      throw new IOException("JWKS contains no usable ES256 P-256 signing key");
    }
    return new Snapshot(Map.copyOf(verifiers), now);
  }

  private boolean isFresh(Snapshot s, Instant now) {
    return now.isBefore(s.fetchedAt().plus(ttl));
  }

  private boolean isUsable(Snapshot s, Instant now) {
    return now.isBefore(s.fetchedAt().plus(maxStale));
  }

  private static Duration positive(Duration d, String name) {
    Objects.requireNonNull(d, name);
    if (d.isNegative() || d.isZero()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return d;
  }
}
