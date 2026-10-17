package in.dexter.identity.domain;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP (RFC 6238 over RFC 4226 HOTP): HMAC-SHA1, 30-second steps, 6 digits — what every authenticator app does by
 * default. {@link #verify} accepts the current step ±{@code window} and returns the matched step, so the caller can
 * reject a step that was already used (replay protection, MASTER_PLAN P05).
 */
public final class Totp {
  public static final int PERIOD_SECONDS = 30;
  public static final int DIGITS = 6;
  public static final int SECRET_BYTES = 20;
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int[] POW10 = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000};

  private Totp() {}

  public static byte[] newSecret() {
    byte[] s = new byte[SECRET_BYTES];
    RANDOM.nextBytes(s);
    return s;
  }

  public static long step(Instant t) {
    return Math.floorDiv(t.getEpochSecond(), PERIOD_SECONDS);
  }

  /** The code for {@code step} (RFC 4226 dynamic truncation). */
  public static String code(byte[] secret, long step, int digits) {
    if (digits < 6 || digits > 8) {
      throw new IllegalArgumentException("digits 6..8");
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(secret, "HmacSHA1"));
      byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
      int offset = h[h.length - 1] & 0x0f;
      int bin = ((h[offset] & 0x7f) << 24) | ((h[offset + 1] & 0xff) << 16) | ((h[offset + 2] & 0xff) << 8)
          | (h[offset + 3] & 0xff);
      String s = Integer.toString(bin % POW10[digits]);
      return "0".repeat(digits - s.length()) + s;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * @return the step in [now-window, now+window] whose code equals {@code code}, or -1. Constant work per step.
   */
  public static long verify(byte[] secret, String code, Instant now, int window) {
    if (code == null || !code.matches("[0-9]{" + DIGITS + "}")) {
      return -1;
    }
    long current = step(now);
    long found = -1;
    for (long s = current - window; s <= current + window; s++) {
      if (java.security.MessageDigest.isEqual(code(secret, s, DIGITS).getBytes(), code.getBytes()) && found < 0) {
        found = s;
      }
    }
    return found;
  }

  /** {@code otpauth://totp/Dexter:<account>?secret=…&issuer=Dexter&…} (Google Authenticator key URI format). */
  public static String otpauthUri(String issuer, String account, byte[] secret) {
    String label = enc(issuer) + ":" + enc(account);
    return "otpauth://totp/" + label + "?secret=" + Base32.encode(secret) + "&issuer=" + enc(issuer)
        + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
  }

  private static String enc(String s) {
    return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
  }

  /** RFC 4648 Base32 without padding (authenticator apps' secret format). */
  public static final class Base32 {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {}

    public static String encode(byte[] data) {
      StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
      int buffer = 0;
      int bits = 0;
      for (byte b : data) {
        buffer = (buffer << 8) | (b & 0xff);
        bits += 8;
        while (bits >= 5) {
          sb.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
          bits -= 5;
        }
      }
      if (bits > 0) {
        sb.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
      }
      return sb.toString();
    }

    public static byte[] decode(String text) {
      String s = text.replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
      ByteBuffer out = ByteBuffer.allocate(s.length() * 5 / 8);
      int buffer = 0;
      int bits = 0;
      for (char c : s.toCharArray()) {
        int v = ALPHABET.indexOf(c);
        if (v < 0) {
          throw new IllegalArgumentException("not base32");
        }
        buffer = (buffer << 5) | v;
        bits += 5;
        if (bits >= 8) {
          out.put((byte) (buffer >> (bits - 8)));
          bits -= 8;
        }
      }
      byte[] r = new byte[out.position()];
      out.flip().get(r);
      return r;
    }
  }
}
