package in.dexter.commerce.domain;

import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * The signed redirect link {@code GET /r/{token}} (MASTER_PLAN P17). The token names what was clicked (a deal or an
 * ad creative), who was shown the link (the account, when the listing was requested with a user token; nobody
 * otherwise) and when it stops working. It is the only input the redirect trusts: the click is attributed to the
 * account inside the signed bytes, never to a header, and an altered or expired token never redirects.
 *
 * <p>Layout (48 bytes, then a 16-byte truncated HMAC-SHA256 over them, base64url without padding = 86 chars):
 * {@code version(1)=1 · kind(1) · target(16) · account(16, zeros = anonymous) · expiresAt epoch seconds(8) ·
 * nonce(6)}. The nonce makes every minted link distinct, so a stored token hash identifies one listing.
 */
public final class ClickToken {
  public static final int MIN_KEY_BYTES = 32;
  private static final byte VERSION = 1;
  private static final int BODY = 48;
  private static final int MAC = 16;
  private static final UUID NOBODY = new UUID(0, 0);
  private static final SecureRandom RANDOM = new SecureRandom();

  public enum Kind {
    DEAL((byte) 'd'), AD((byte) 'a');

    private final byte code;

    Kind(byte code) {
      this.code = code;
    }

    static @Nullable Kind of(byte b) {
      for (Kind k : values()) {
        if (k.code == b) {
          return k;
        }
      }
      return null;
    }
  }

  public record Claims(Kind kind, UUID targetId, @Nullable UUID accountId, Instant expiresAt) {}

  public enum Outcome { OK, INVALID, EXPIRED }

  public record Verified(Outcome outcome, @Nullable Claims claims) {}

  private final byte[] key;

  public ClickToken(byte[] key) {
    if (key == null || key.length < MIN_KEY_BYTES) {
      throw new IllegalArgumentException("click token key must be at least " + MIN_KEY_BYTES + " bytes");
    }
    this.key = key.clone();
  }

  public String mint(Claims claims) {
    byte[] nonce = new byte[6];
    RANDOM.nextBytes(nonce);
    ByteBuffer b = ByteBuffer.allocate(BODY + MAC);
    b.put(VERSION).put(claims.kind().code);
    putUuid(b, claims.targetId());
    putUuid(b, claims.accountId() == null ? NOBODY : claims.accountId());
    b.putLong(claims.expiresAt().getEpochSecond());
    b.put(nonce);
    byte[] all = b.array();
    System.arraycopy(mac(all), 0, all, BODY, MAC);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(all);
  }

  public Verified verify(@Nullable String token, Instant now) {
    if (token == null || token.length() != 86) {
      return new Verified(Outcome.INVALID, null);
    }
    byte[] all;
    try {
      all = Base64.getUrlDecoder().decode(token);
    } catch (IllegalArgumentException e) {
      return new Verified(Outcome.INVALID, null);
    }
    if (all.length != BODY + MAC
        || !MessageDigest.isEqual(mac(all), Arrays.copyOfRange(all, BODY, BODY + MAC))) {
      return new Verified(Outcome.INVALID, null);
    }
    ByteBuffer b = ByteBuffer.wrap(all);
    if (b.get() != VERSION) {
      return new Verified(Outcome.INVALID, null);
    }
    Kind kind = Kind.of(b.get());
    if (kind == null) {
      return new Verified(Outcome.INVALID, null);
    }
    UUID target = new UUID(b.getLong(), b.getLong());
    UUID account = new UUID(b.getLong(), b.getLong());
    Instant exp = Instant.ofEpochSecond(b.getLong());
    Claims claims = new Claims(kind, target, NOBODY.equals(account) ? null : account, exp);
    if (!now.isBefore(exp)) {
      return new Verified(Outcome.EXPIRED, claims);
    }
    return new Verified(Outcome.OK, claims);
  }

  /** SHA-256 of the token text, for the raw click row. */
  public static byte[] hash(String token) {
    return Hashing.sha256(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
  }

  private byte[] mac(byte[] all) {
    try {
      Mac m = Mac.getInstance("HmacSHA256");
      m.init(new SecretKeySpec(key, "HmacSHA256"));
      m.update(all, 0, BODY);
      return Arrays.copyOf(m.doFinal(), MAC);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void putUuid(ByteBuffer b, UUID id) {
    b.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
  }
}
