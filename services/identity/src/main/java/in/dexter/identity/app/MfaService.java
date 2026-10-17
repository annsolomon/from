package in.dexter.identity.app;

import in.dexter.contracts.events.identity.SecurityAlertV2;
import in.dexter.core.error.DexterException;
import in.dexter.core.error.NotFoundException;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.OutboxWriter;
import in.dexter.identity.app.MfaStore.TotpRow;
import in.dexter.identity.domain.IdentityErrors;
import in.dexter.identity.domain.RecoveryCodes;
import in.dexter.identity.domain.Totp;
import in.dexter.security.AuthContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * /v1/me/mfa (MASTER_PLAN P05): TOTP enrolment (pending secret → confirmed by a first code, then 10 recovery codes
 * shown once), disable and recovery-code regeneration. Changing MFA needs a session that itself used MFA (the
 * token's {@code amr} contains {@code mfa}) once MFA is enrolled — a stale pre-enrolment token cannot remove it.
 */
public final class MfaService {
  /** What the authenticator app needs (the secret is shown once, during enrolment only). */
  public record Enrollment(String secret, String otpauthUri, int digits, int period) {}

  public record Status(boolean totpEnrolled, boolean pending, int recoveryCodesLeft) {}

  private final TenantTx tx;
  private final MfaStore store;
  private final AccountStore accounts;
  private final FieldCipher cipher;
  private final UserSecondFactor factor;
  private final OutboxWriter outbox;
  private final SecuritySettings settings;
  private final Clock clock;

  public MfaService(TenantTx tx, MfaStore store, AccountStore accounts, FieldCipher cipher, UserSecondFactor factor,
      OutboxWriter outbox, SecuritySettings settings, Clock clock) {
    this.tx = tx;
    this.store = store;
    this.accounts = accounts;
    this.cipher = cipher;
    this.factor = factor;
    this.outbox = outbox;
    this.settings = settings;
    this.clock = clock;
  }

  public Status status(AuthContext caller) {
    return tx.read(caller.tenant(), () -> {
      TotpRow row = store.find(caller.accountId()).orElse(null);
      return new Status(row != null && row.confirmedAt() != null, row != null && row.confirmedAt() == null,
          store.recoveryCodesLeft(caller.accountId()));
    });
  }

  /** Step-up on the controller. Replaces a pending secret; refuses when MFA is already confirmed. */
  public Enrollment enroll(AuthContext caller) {
    byte[] secret = Totp.newSecret();
    String b32 = Totp.Base32.encode(secret);
    return tx.write(caller.tenant(), () -> {
      String email = accounts.find(caller.accountId()).orElseThrow(() -> new NotFoundException("account")).email();
      if (!store.putPending(caller.accountId(), cipher.encrypt(caller.accountId(), UserSecondFactor.TOTP_FIELD, b32))) {
        throw new DexterException(IdentityErrors.MFA_ALREADY_ENROLLED, "Disable the current authenticator first");
      }
      return new Enrollment(b32, Totp.otpauthUri(settings.totpIssuer(), email, secret), Totp.DIGITS,
          Totp.PERIOD_SECONDS);
    });
  }

  /** The first valid code confirms the secret; returns 10 recovery codes (only now, never again). */
  public List<String> confirm(AuthContext caller, String code) {
    Instant now = clock.instant();
    List<String> codes = RecoveryCodes.generate();
    return tx.write(caller.tenant(), () -> {
      TotpRow row = store.find(caller.accountId()).orElse(null);
      if (row == null) {
        throw new DexterException(IdentityErrors.MFA_NOT_ENROLLED, "Start the enrolment first");
      }
      if (row.confirmedAt() != null) {
        throw new DexterException(IdentityErrors.MFA_ALREADY_ENROLLED, "The authenticator is already confirmed");
      }
      long step = Totp.verify(factor.secret(caller.accountId(), row.secretEnc()), code == null ? "" : code.strip(), now,
          settings.totpWindow());
      if (step < 0) {
        throw new DexterException(IdentityErrors.INVALID_MFA_CODE, "The code does not match; check the device clock");
      }
      store.confirm(caller.accountId(), step, now);
      store.replaceRecoveryCodes(caller.accountId(), hashes(codes));
      alert(caller, SecurityAlertV2.MFA_ENABLED, now);
      return codes;
    });
  }

  /** Step-up (with MFA) on the controller. */
  public void disable(AuthContext caller) {
    Instant now = clock.instant();
    tx.writeVoid(caller.tenant(), () -> {
      TotpRow row = store.find(caller.accountId())
          .orElseThrow(() -> new DexterException(IdentityErrors.MFA_NOT_ENROLLED, "No authenticator to remove"));
      if (row.confirmedAt() != null) {
        requireMfaSession(caller);
      }
      store.delete(caller.accountId());
      if (row.confirmedAt() != null) {
        alert(caller, SecurityAlertV2.MFA_DISABLED, now);
      }
    });
  }

  /** Step-up (with MFA) on the controller. Old codes stop working. */
  public List<String> regenerate(AuthContext caller) {
    requireMfaSession(caller);
    Instant now = clock.instant();
    List<String> codes = RecoveryCodes.generate();
    return tx.write(caller.tenant(), () -> {
      if (store.find(caller.accountId()).filter(r -> r.confirmedAt() != null).isEmpty()) {
        throw new DexterException(IdentityErrors.MFA_NOT_ENROLLED, "Enable an authenticator first");
      }
      store.replaceRecoveryCodes(caller.accountId(), hashes(codes));
      alert(caller, SecurityAlertV2.RECOVERY_CODES_REGENERATED, now);
      return codes;
    });
  }

  private void requireMfaSession(AuthContext caller) {
    if (!caller.amr().contains(AuthContext.AMR_MFA)) {
      throw new DexterException(in.dexter.security.SecurityErrors.STEP_UP_REQUIRED,
          "Sign in again with your second factor to change MFA");
    }
  }

  private List<byte[]> hashes(List<String> codes) {
    List<byte[]> out = new ArrayList<>(codes.size());
    codes.forEach(c -> out.add(factor.recoveryHash(RecoveryCodes.normalize(c))));
    return out;
  }

  private void alert(AuthContext caller, String kind, Instant now) {
    outbox.append(IdentityEvents.SECURITY_ALERT, caller.accountId(), caller.accountId(), 1,
        new SecurityAlertV2(kind, caller.accountId(), now.toString()));
  }
}
