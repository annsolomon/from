package in.dexter.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

/**
 * What a key is bound to (MASTER_PLAN 5.2): method, path and {@code sha256(method|path|canonical
 * body)}. The same key with a different fingerprint is {@code 422 IDEMPOTENCY_KEY_REUSED}.
 */
public record IdempotentRequest(String method, String path, byte[] hash) {
  public IdempotentRequest {
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(path, "path");
    method = method.toUpperCase(Locale.ROOT);
    if (hash == null || hash.length != 32) {
      throw new IllegalArgumentException("hash must be a sha256");
    }
    hash = hash.clone();
    if (path.length() > 512) {
      throw new IllegalArgumentException("path too long");
    }
  }

  /** Fingerprint of a request whose body is a DTO (or a raw JSON string), via {@link CanonicalJson}. */
  public static IdempotentRequest of(String method, String path, Object body, ObjectMapper mapper) {
    return ofCanonical(method, path, CanonicalJson.of(mapper, body));
  }

  /** Fingerprint from an already-canonical body text. */
  public static IdempotentRequest ofCanonical(String method, String path, String canonicalBody) {
    String m = method.toUpperCase(Locale.ROOT);
    return new IdempotentRequest(m, path, sha256(m + "|" + path + "|" + canonicalBody));
  }

  static byte[] sha256(String s) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The hash must not be mutated by callers. */
  @Override
  public byte[] hash() {
    return hash.clone();
  }

  public boolean sameAs(String otherMethod, String otherPath, byte[] otherHash) {
    return method.equals(otherMethod) && path.equals(otherPath) && MessageDigest.isEqual(hash, otherHash);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof IdempotentRequest r && r.method.equals(method) && r.path.equals(path)
        && Arrays.equals(r.hash, hash);
  }

  @Override
  public int hashCode() {
    return Objects.hash(method, path, Arrays.hashCode(hash));
  }

  @Override
  public String toString() {
    return "IdempotentRequest[" + method + " " + path + " " + HexFormat.of().formatHex(hash, 0, 6) + "]";
  }
}
