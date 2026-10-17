package in.dexter.identity.app;

import java.util.UUID;

/**
 * Column encryption (MASTER_PLAN 7.4): AES-256-GCM with the account's data key (envelope: DEK wrapped by the KEK).
 * Must run inside the account's tenant transaction (the DEK row is RLS-protected; it is created on first use).
 */
public interface FieldCipher {
  byte[] encrypt(UUID accountId, String field, String plaintext);

  String decrypt(UUID accountId, String field, byte[] ciphertext);

  /** Keyed hash (HMAC-SHA256, server pepper) for uniqueness checks on encrypted values. */
  byte[] blindIndex(String field, String normalized);
}
