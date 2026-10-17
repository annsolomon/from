package in.dexter.identity.infra;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM with a random 96-bit nonce and associated data (binds a ciphertext to its row and field, so a value
 * copied to another account or column fails to decrypt). Wire form: {@code 0x01 | nonce(12) | ciphertext+tag(16)}.
 */
final class Aead {
  static final byte VERSION = 1;
  private static final int NONCE = 12;
  private static final int TAG_BITS = 128;
  private static final SecureRandom RANDOM = new SecureRandom();

  private Aead() {}

  static byte[] seal(byte[] key, byte[] aad, byte[] plaintext) {
    requireKey(key);
    byte[] nonce = new byte[NONCE];
    RANDOM.nextBytes(nonce);
    try {
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
      c.updateAAD(aad);
      byte[] ct = c.doFinal(plaintext);
      return ByteBuffer.allocate(1 + NONCE + ct.length).put(VERSION).put(nonce).put(ct).array();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES-GCM seal failed", e);
    }
  }

  static byte[] open(byte[] key, byte[] aad, byte[] sealed) {
    requireKey(key);
    if (sealed == null || sealed.length < 1 + NONCE + TAG_BITS / 8 || sealed[0] != VERSION) {
      throw new IllegalStateException("not an AES-GCM v1 ciphertext");
    }
    try {
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, sealed, 1, NONCE));
      c.updateAAD(aad);
      return c.doFinal(sealed, 1 + NONCE, sealed.length - 1 - NONCE);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES-GCM open failed (wrong key or associated data, or tampered)", e);
    }
  }

  private static void requireKey(byte[] key) {
    if (key == null || key.length != 32) {
      throw new IllegalArgumentException("AES-256 key must be 32 bytes");
    }
  }
}
