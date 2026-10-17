package in.dexter.identity.app;

import in.dexter.core.error.DexterException;
import in.dexter.core.error.ValidationException;
import in.dexter.core.id.Ids;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import in.dexter.identity.app.AccountStore.Account;
import in.dexter.identity.app.PreAuthStore.AccountRef;
import in.dexter.identity.app.PreAuthStore.PasswordReset;
import in.dexter.identity.domain.Emails;
import in.dexter.identity.domain.IdentityErrors;
import in.dexter.identity.domain.IdentityTenants;
import in.dexter.identity.domain.PasswordPolicy;
import in.dexter.identity.domain.RevokeReason;
import in.dexter.identity.domain.SecretTokens;
import in.dexter.security.AuthContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Forgot / reset / change password (MASTER_PLAN P04). Reset and change sign other sessions out. */
public final class PasswordService {
  static final String RESET = "password_reset";

  private final TenantTx tx;
  private final PreAuthStore preAuth;
  private final AccountStore accounts;
  private final SessionStore sessions;
  private final RefreshTokenStore refreshTokens;
  private final VerificationTokenStore tokens;
  private final PasswordHasher hasher;
  private final PasswordPolicy.BreachedList breached;
  private final Mailer mailer;
  private final AuthService auth;
  private final IdentitySettings settings;
  private final Clock clock;

  public PasswordService(TenantTx tx, PreAuthStore preAuth, AccountStore accounts, SessionStore sessions,
      RefreshTokenStore refreshTokens, VerificationTokenStore tokens, PasswordHasher hasher,
      PasswordPolicy.BreachedList breached, Mailer mailer, AuthService auth, IdentitySettings settings, Clock clock) {
    this.tx = tx;
    this.preAuth = preAuth;
    this.accounts = accounts;
    this.sessions = sessions;
    this.refreshTokens = refreshTokens;
    this.tokens = tokens;
    this.hasher = hasher;
    this.breached = breached;
    this.mailer = mailer;
    this.auth = auth;
    this.settings = settings;
    this.clock = clock;
  }

  /** Always succeeds (202): whether the email exists is never revealed. Throttled per account and hour. */
  public void forgot(String rawEmail) {
    String email = Emails.normalize(rawEmail);
    if (email == null) {
      return;
    }
    AccountRef acct = tx.read(IdentityTenants.SYSTEM, () -> preAuth.findAccount(email)).orElse(null);
    if (acct == null || !("active".equals(acct.status()) || "pending_verification".equals(acct.status()))) {
      return;
    }
    Instant now = clock.instant();
    String token = SecretTokens.newToken();
    tx.writeVoid(Tenant.user(acct.accountId()), () -> {
      if (tokens.countSince(acct.accountId(), RESET, now.minusSeconds(3600)) >= settings.tokensPerHour()) {
        return;
      }
      tokens.issue(Ids.newId(), acct.accountId(), RESET, SecretTokens.hash(token), now.plus(settings.passwordResetTtl()));
      mailer.sendAfterCommit(IdentityMails.reset(email, IdentityMails.link(settings.resetLink(), token),
          settings.passwordResetTtl().toMinutes()));
    });
  }

  /** Single-use token → new password, every session revoked (one event each). */
  public void reset(String token, String newPassword) {
    List<in.dexter.core.error.FieldError> errors = PasswordPolicy.check("newPassword", newPassword, null, breached);
    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
    if (!SecretTokens.looksValid(token)) {
      throw EmailVerificationService.invalid();
    }
    String hash = hasher.hash(newPassword);
    Instant now = clock.instant();
    tx.writeVoid(IdentityTenants.SYSTEM, () -> {
      PasswordReset r = preAuth.resetPassword(SecretTokens.hash(token), hash)
          .orElseThrow(EmailVerificationService::invalid);
      r.revokedSessions().forEach(s -> auth.sessionRevoked(r.accountId(), s, RevokeReason.PASSWORD_RESET, now));
    });
  }

  /** Step-up on the controller. Keeps the caller's session, signs every other one out. */
  public void change(AuthContext caller, String currentPassword, String newPassword) {
    Tenant tenant = caller.tenant();
    Account account = tx.read(tenant, () -> accounts.find(caller.accountId()))
        .orElseThrow(() -> new DexterException(IdentityErrors.ACCOUNT_UNAVAILABLE, "Account unavailable"));
    if (currentPassword == null || !hasher.matches(currentPassword, account.passwordHash())) {
      throw new DexterException(IdentityErrors.CURRENT_PASSWORD_INVALID, "The current password is incorrect");
    }
    List<in.dexter.core.error.FieldError> errors =
        PasswordPolicy.check("newPassword", newPassword, account.email(), breached);
    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
    String hash = hasher.hash(newPassword);
    Instant now = clock.instant();
    UUID keep = UUID.fromString(caller.sessionId());
    tx.writeVoid(tenant, () -> {
      accounts.updatePasswordHash(caller.accountId(), hash, true);
      List<UUID> revoked = sessions.revokeAll(caller.accountId(), keep, RevokeReason.PASSWORD_CHANGE, now);
      refreshTokens.revokeSessions(caller.accountId(), revoked, now);
      revoked.forEach(s -> auth.sessionRevoked(caller.accountId(), s, RevokeReason.PASSWORD_CHANGE, now));
      mailer.sendAfterCommit(IdentityMails.passwordChanged(account.email()));
    });
  }
}
