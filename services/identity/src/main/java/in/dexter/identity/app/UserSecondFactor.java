package in.dexter.identity.app;

import in.dexter.contracts.events.identity.SecurityAlertV2;
import in.dexter.events.OutboxWriter;
import in.dexter.identity.app.MfaStore.TotpRow;
import in.dexter.identity.domain.RecoveryCodes;
import in.dexter.identity.domain.Totp;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Checks a user's second factor inside the account's tenant transaction: a TOTP code (step not used before — the
 * replay guard advances {@code last_used_step} atomically) or a recovery code (single use, raises
 * {@code RECOVERY_CODE_USED}). Returns the RFC 8176 {@code amr} values to add, or null when it fails.
 */
public final class UserSecondFactor {
  static final String TOTP_FIELD = "totp";
  static final String RECOVERY_FIELD = "recovery";
  static final List<String> AMR_OTP = List.of("pwd", "mfa", "otp");
  static final List<String> AMR_RECOVERY = List.of("pwd", "mfa");

  private final MfaStore store;
  private final FieldCipher cipher;
  private final OutboxWriter outbox;
  private final SecuritySettings settings;
  private final Clock clock;

  public UserSecondFactor(MfaStore store, FieldCipher cipher, OutboxWriter outbox, SecuritySettings settings,
      Clock clock) {
    this.store = store;
    this.cipher = cipher;
    this.outbox = outbox;
    this.settings = settings;
    this.clock = clock;
  }

  /** Must run in the account's tenant write transaction. */
  List<String> verify(UUID accountId, String code, String recoveryCode) {
    Instant now = clock.instant();
    if (code != null && !code.isBlank()) {
      TotpRow row = store.find(accountId).filter(r -> r.confirmedAt() != null).orElse(null);
      if (row == null) {
        return null;
      }
      byte[] secret = secret(accountId, row.secretEnc());
      long step = Totp.verify(secret, code.strip(), now, settings.totpWindow());
      return step >= 0 && store.advanceStep(accountId, step) ? AMR_OTP : null;
    }
    String norm = RecoveryCodes.normalize(recoveryCode);
    if (norm != null && store.useRecoveryCode(accountId, recoveryHash(norm), now)) {
      outbox.append(IdentityEvents.SECURITY_ALERT, accountId, accountId, 1,
          new SecurityAlertV2(SecurityAlertV2.RECOVERY_CODE_USED, accountId, now.toString()));
      return AMR_RECOVERY;
    }
    return null;
  }

  byte[] secret(UUID accountId, byte[] secretEnc) {
    return Totp.Base32.decode(cipher.decrypt(accountId, TOTP_FIELD, secretEnc));
  }

  byte[] recoveryHash(String normalized) {
    return cipher.blindIndex(RECOVERY_FIELD, normalized);
  }

  static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }
}
