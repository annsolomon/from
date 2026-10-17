package in.dexter.identity.app;

import in.dexter.core.error.ConflictException;
import in.dexter.core.error.DexterException;
import in.dexter.core.error.FieldError;
import in.dexter.core.error.ValidationException;
import in.dexter.core.id.Ids;
import in.dexter.db.tenant.TenantTx;
import in.dexter.identity.app.AdminStore.Admin;
import in.dexter.identity.domain.IdentityErrors;
import in.dexter.identity.domain.Totp;
import in.dexter.security.AuthContext;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Break-glass (MASTER_PLAN 7.2): an eligible admin holds SUPER_ADMIN for at most one hour, with a written reason and
 * a fresh TOTP code. The elevation is a row (audited, evented); the access token that carries SUPER_ADMIN expires no
 * later than the elevation, and SUPER_ADMIN use-cases re-check the row, so ending it early takes effect at once.
 */
public final class ElevationService {
  private static final Logger log = LoggerFactory.getLogger(ElevationService.class);
  static final int REASON_MIN = 20;
  static final int REASON_MAX = 500;

  public record Elevated(UUID elevationId, Instant expiresAt, String accessToken, long expiresIn) {}

  public record Ended(boolean ended, String accessToken, long expiresIn) {}

  private final TenantTx tx;
  private final AdminStore store;
  private final SecretSealer sealer;
  private final Throttles throttles;
  private final AdminAuthService auth;
  private final SecuritySettings security;
  private final Clock clock;

  public ElevationService(TenantTx tx, AdminStore store, SecretSealer sealer, Throttles throttles,
      AdminAuthService auth, SecuritySettings security, Clock clock) {
    this.tx = tx;
    this.store = store;
    this.sealer = sealer;
    this.throttles = throttles;
    this.auth = auth;
    this.security = security;
    this.clock = clock;
  }

  public Elevated start(AuthContext caller, String reason, String code) {
    AdminGuard.require(caller);
    String why = reason == null ? "" : reason.strip();
    if (why.length() < REASON_MIN || why.length() > REASON_MAX) {
      throw new ValidationException(List.of(new FieldError("reason", "LENGTH",
          "Explain why break-glass is needed (" + REASON_MIN + "-" + REASON_MAX + " characters)")));
    }
    UUID adminId = caller.accountId();
    Instant attempt = clock.instant();
    String key = Throttles.adminMfa(adminId);
    throttles.check(attempt, key, security.mfaPolicy(), null);
    Instant now = attempt.truncatedTo(ChronoUnit.MICROS);
    Instant expires = now.plus(security.elevationMax());
    UUID id = Ids.newId();
    record Result(boolean codeOk, UUID id) {}
    Result r = tx.write(AdminAuthService.tenant(adminId), () -> {
      Admin a = store.find(adminId).filter(x -> "active".equals(x.status()))
          .orElseThrow(() -> new DexterException(IdentityErrors.NOT_BREAK_GLASS_ELIGIBLE, "Admin not active"));
      if (!a.breakGlassEligible()) {
        throw new DexterException(IdentityErrors.NOT_BREAK_GLASS_ELIGIBLE, "This admin may not use break-glass");
      }
      if (store.activeElevation(adminId, now).isPresent()) {
        throw new ConflictException("A break-glass elevation is already active; end it first");
      }
      AdminStore.Totp t = store.totp(adminId).filter(x -> x.confirmedAt() != null).orElse(null);
      long step = t == null ? -1 : Totp.verify(sealer.open(t.kekId(), AdminAuthService.aad(adminId), t.secretEnc()),
          code == null ? "" : code.strip(), attempt, security.totpWindow());
      if (step < 0 || !store.advanceTotpStep(adminId, step)) {
        return new Result(false, null);
      }
      store.insertElevation(id, adminId, why, now, expires);
      store.audit(adminId, "BREAK_GLASS_STARTED", "elevation", id, AdminDirectoryService.json("reason", why));
      auth.appendAction(adminId, "BREAK_GLASS_STARTED", "elevation", id, why, null, now);
      return new Result(true, id);
    });
    if (!r.codeOk()) {
      throttles.fail(attempt, key, security.mfaPolicy(), null);
      throw new DexterException(IdentityErrors.INVALID_MFA_CODE, "The code is not valid (or was already used)");
    }
    throttles.succeed(key);
    log.warn("BREAK_GLASS_STARTED admin={} elevation={} until={}", adminId, id, expires);
    AccessTokens.Issued at = auth.reissue(caller);
    return new Elevated(r.id(), expires, at.token(), at.expiresIn());
  }

  public Ended end(AuthContext caller) {
    AdminGuard.require(caller);
    UUID adminId = caller.accountId();
    Instant now = clock.instant();
    boolean ended = tx.write(AdminAuthService.tenant(adminId), () -> {
      UUID id = store.revokeActiveElevation(adminId, now).orElse(null);
      if (id == null) {
        return false;
      }
      store.audit(adminId, "BREAK_GLASS_ENDED", "elevation", id, "{}");
      auth.appendAction(adminId, "BREAK_GLASS_ENDED", "elevation", id, "", null, now);
      return true;
    });
    if (ended) {
      log.info("BREAK_GLASS_ENDED admin={}", adminId);
    }
    AccessTokens.Issued at = auth.reissue(caller);
    return new Ended(ended, at.token(), at.expiresIn());
  }

  /** Server-side check behind every SUPER_ADMIN use-case (the token alone is not enough). */
  public void requireActive(AuthContext caller) {
    UUID adminId = caller.accountId();
    Instant now = clock.instant();
    boolean active = caller.hasRole(in.dexter.core.tenant.Tenant.SUPER_ADMIN)
        && tx.read(AdminAuthService.tenant(adminId), () -> store.activeElevation(adminId, now)).isPresent();
    if (!active) {
      throw new DexterException(in.dexter.core.error.CoreErrors.FORBIDDEN,
          "Break-glass elevation required (it may have expired or been ended)");
    }
  }
}
