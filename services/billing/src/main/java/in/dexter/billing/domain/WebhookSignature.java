package in.dexter.billing.domain;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Webhook signatures (MASTER_PLAN 7.3): {@code Webhook-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256>} over
 * {@code "<t>." + body}, with the timestamp inside a tolerance window (±5 minutes) so a captured delivery cannot be
 * replayed later. The comparison is constant-time. The replay of a delivery inside the window is caught one layer up,
 * by the provider's event id (webhook_events primary key).
 */
public final class WebhookSignature {
  public static final Duration TOLERANCE = Duration.ofMinutes(5);

  public enum Check { OK, MISSING, MALFORMED, STALE, MISMATCH }

  private WebhookSignature() {}

  public static String header(byte[] secret, long unixSeconds, byte[] body) {
    return "t=" + unixSeconds + ",v1=" + HexFormat.of().formatHex(mac(secret, unixSeconds, body));
  }

  public static Check verify(byte[] secret, String header, byte[] body, Instant now, Duration tolerance) {
    if (header == null || header.isBlank()) {
      return Check.MISSING;
    }
    Long t = null;
    byte[] given = null;
    for (String part : header.split(",")) {
      String p = part.strip();
      if (p.startsWith("t=")) {
        try {
          t = Long.parseLong(p.substring(2));
        } catch (NumberFormatException e) {
          return Check.MALFORMED;
        }
      } else if (p.startsWith("v1=")) {
        String hex = p.substring(3);
        if (hex.length() != 64) {
          return Check.MALFORMED;
        }
        try {
          given = HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
          return Check.MALFORMED;
        }
      }
    }
    if (t == null || given == null) {
      return Check.MALFORMED;
    }
    long skew = Math.abs(now.getEpochSecond() - t);
    if (skew > tolerance.toSeconds()) {
      return Check.STALE;
    }
    return MessageDigest.isEqual(mac(secret, t, body), given) ? Check.OK : Check.MISMATCH;
  }

  private static byte[] mac(byte[] secret, long t, byte[] body) {
    if (secret == null || secret.length < 16) {
      throw new IllegalArgumentException("webhook secret must be at least 16 bytes");
    }
    try {
      Mac m = Mac.getInstance("HmacSHA256");
      m.init(new SecretKeySpec(secret, "HmacSHA256"));
      m.update((t + ".").getBytes(StandardCharsets.US_ASCII));
      return m.doFinal(body);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException(e);
    }
  }
}
