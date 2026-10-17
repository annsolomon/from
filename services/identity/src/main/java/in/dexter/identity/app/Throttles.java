package in.dexter.identity.app;

import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.identity.domain.SecretTokens;
import in.dexter.identity.domain.ThrottlePolicy;
import in.dexter.web.error.ProblemDetailsAdvice;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Throttle keys and the uniform 429 (MASTER_PLAN 7.1). Email keys are a hash of the lower-cased text the client
 * sent, so an unknown email is throttled exactly like a known one (no enumeration through 429s).
 */
public final class Throttles {
  private final LoginThrottle throttle;
  private final SecuritySettings s;

  public Throttles(LoginThrottle throttle, SecuritySettings s) {
    this.throttle = throttle;
    this.s = s;
  }

  public static String userLogin(String rawEmail) {
    return "u:" + digest(rawEmail);
  }

  public static String adminLogin(String rawEmail) {
    return "a:" + digest(rawEmail);
  }

  public static String userMfa(UUID accountId) {
    return "m:" + accountId;
  }

  public static String adminMfa(UUID adminId) {
    return "am:" + adminId;
  }

  public static String stepUp(UUID accountId) {
    return "s:" + accountId;
  }

  public static String ip(String ip) {
    return "ip:" + (ip == null ? "unknown" : ip);
  }

  /** Refuses with 429 + Retry-After when any key is throttled. {@code ip} null = no per-IP bucket (step-up). */
  public void check(Instant now, String principalKey, ThrottlePolicy policy, String ip) {
    if (ip != null) {
      refuseIf(throttle.check(ip(ip), s.ipPolicy(), now));
    }
    refuseIf(throttle.check(principalKey, policy, now));
  }

  /** Counts a failure on the principal (and the IP); true when the principal just became locked. */
  public boolean fail(Instant now, String principalKey, ThrottlePolicy policy, String ip) {
    if (ip != null) {
      throttle.fail(ip(ip), s.ipPolicy(), now);
    }
    return throttle.fail(principalKey, policy, now).lockedNow();
  }

  public void succeed(String principalKey) {
    throttle.reset(principalKey);
  }

  private static void refuseIf(LoginThrottle.Verdict v) {
    if (!v.allowed()) {
      long seconds = Math.max(1, (v.retryAfterMillis() + 999) / 1000);
      throw new DexterException(CoreErrors.RATE_LIMITED, "Too many attempts; try again later", List.of(),
          Map.of(ProblemDetailsAdvice.RETRY_AFTER_PROPERTY, seconds), null);
    }
  }

  private static String digest(String rawEmail) {
    String norm = rawEmail == null ? "" : rawEmail.strip().toLowerCase(Locale.ROOT);
    return HexFormat.of().formatHex(SecretTokens.sha256(norm.getBytes(StandardCharsets.UTF_8)), 0, 16);
  }
}
