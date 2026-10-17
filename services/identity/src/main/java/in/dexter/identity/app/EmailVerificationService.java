package in.dexter.identity.app;

import in.dexter.contracts.events.identity.AccountVerifiedV1;
import in.dexter.core.error.DexterException;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.OutboxWriter;
import in.dexter.identity.app.PreAuthStore.VerifiedEmail;
import in.dexter.identity.domain.IdentityErrors;
import in.dexter.identity.domain.IdentityTenants;
import in.dexter.identity.domain.SecretTokens;
import java.time.Clock;

/** POST /v1/auth/verify-email: single-use token → account active + {@code identity.account.verified}. */
public final class EmailVerificationService {
  private final TenantTx tx;
  private final PreAuthStore preAuth;
  private final OutboxWriter outbox;
  private final Clock clock;

  public EmailVerificationService(TenantTx tx, PreAuthStore preAuth, OutboxWriter outbox, Clock clock) {
    this.tx = tx;
    this.preAuth = preAuth;
    this.outbox = outbox;
    this.clock = clock;
  }

  /** @return true when this call activated the account (false: it was already active) */
  public boolean verify(String token) {
    if (!SecretTokens.looksValid(token)) {
      throw invalid();
    }
    return tx.write(IdentityTenants.SYSTEM, () -> {
      VerifiedEmail v = preAuth.verifyEmail(SecretTokens.hash(token)).orElseThrow(EmailVerificationService::invalid);
      if (v.activated()) {
        outbox.append(IdentityEvents.ACCOUNT_VERIFIED, v.accountId(), v.accountId(), v.version(),
            new AccountVerifiedV1(v.accountId(), clock.instant().toString()));
      }
      return v.activated();
    });
  }

  static DexterException invalid() {
    return new DexterException(IdentityErrors.INVALID_TOKEN, "The link is invalid or has expired; request a new one");
  }
}
