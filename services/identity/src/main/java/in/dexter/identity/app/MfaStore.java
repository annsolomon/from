package in.dexter.identity.app;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@code identity.mfa_totp} + {@code identity.recovery_codes} of the current account (tenant transaction). */
public interface MfaStore {
  record TotpRow(byte[] secretEnc, Instant confirmedAt, long lastUsedStep) {}

  Optional<TotpRow> find(UUID accountId);

  /** Creates or replaces an unconfirmed secret (never touches a confirmed one). Returns false if confirmed. */
  boolean putPending(UUID accountId, byte[] secretEnc);

  void confirm(UUID accountId, long step, Instant now);

  /** Replay guard: moves last_used_step forward; false when {@code step} was already used. */
  boolean advanceStep(UUID accountId, long step);

  void delete(UUID accountId);

  void replaceRecoveryCodes(UUID accountId, List<byte[]> hashes);

  /** Marks one unused code used; false when unknown or already used. */
  boolean useRecoveryCode(UUID accountId, byte[] hash, Instant now);

  int recoveryCodesLeft(UUID accountId);
}
