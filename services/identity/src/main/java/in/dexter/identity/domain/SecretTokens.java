package in.dexter.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Opaque secrets handed to clients (refresh tokens, email links): 256 random bits, base64url without padding
 * (43 chars). Only {@link #hash(String)} (SHA-256) is stored, so a database leak reveals no usable token.
 */
public final class SecretTokens {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Pattern SHAPE = Pattern.compile("[A-Za-z0-9_-]{43}");

  private SecretTokens() {}

  public static String newToken() {
    byte[] b = new byte[32];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  /** True for something that could be one of our tokens (cheap pre-check before any lookup). */
  public static boolean looksValid(String token) {
    return token != null && SHAPE.matcher(token).matches();
  }

  public static byte[] hash(String token) {
    return sha256(token.getBytes(StandardCharsets.US_ASCII));
  }

  public static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Constant-time comparison. */
  public static boolean equal(byte[] a, byte[] b) {
    return MessageDigest.isEqual(a, b);
  }
}
