package in.dexter.identity.infra;

import in.dexter.identity.app.FieldCipher;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Envelope encryption (MASTER_PLAN 7.4): each account has a random 256-bit data key (DEK), stored wrapped by the KEK
 * in {@code account_keys} (aad = {@code dek:<account>}); fields are AES-GCM under the DEK (aad = {@code
 * <account>:<field>}). Deleting/destroying the DEK crypto-shreds every field (P18). The DEK is created on first use,
 * so seeded accounts without one work too.
 */
public final class EnvelopeCipher implements FieldCipher {
  private static final SecureRandom RANDOM = new SecureRandom();

  private final AccountKeyRepository keys;
  private final KekRing kek;
  private final byte[] pepper;

  public EnvelopeCipher(JdbcClient jdbc, KekRing kek, byte[] pepper) {
    this.keys = new AccountKeyRepository(jdbc);
    this.kek = kek;
    if (pepper == null || pepper.length < 32) {
      throw new IllegalArgumentException("blind-index pepper must be at least 32 bytes");
    }
    this.pepper = pepper.clone();
  }

  @Override
  public byte[] encrypt(UUID accountId, String field, String plaintext) {
    return Aead.seal(dek(accountId, true), aad(accountId, field), plaintext.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String decrypt(UUID accountId, String field, byte[] ciphertext) {
    byte[] dek = dek(accountId, false);
    if (dek == null) {
      return null; // crypto-shredded or never written
    }
    return new String(Aead.open(dek, aad(accountId, field), ciphertext), StandardCharsets.UTF_8);
  }

  @Override
  public byte[] blindIndex(String field, String normalized) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
      return mac.doFinal((field + ":" + normalized).getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private byte[] dek(UUID accountId, boolean create) {
    AccountKeyRepository.WrappedKey w = keys.find(accountId).orElse(null);
    if (w == null && create) {
      byte[] fresh = new byte[32];
      RANDOM.nextBytes(fresh);
      keys.insertIfAbsent(accountId, kek.wrap(dekAad(accountId), fresh), kek.activeId());
      w = keys.find(accountId).orElseThrow();
    }
    if (w == null) {
      return null;
    }
    if (w.destroyed()) {
      throw new IllegalStateException("data key of account " + accountId + " was destroyed");
    }
    return kek.unwrap(w.kekId(), dekAad(accountId), w.dekEnc());
  }

  private static byte[] dekAad(UUID accountId) {
    return ("dek:" + accountId).getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] aad(UUID accountId, String field) {
    return (accountId + ":" + field).getBytes(StandardCharsets.UTF_8);
  }
}
