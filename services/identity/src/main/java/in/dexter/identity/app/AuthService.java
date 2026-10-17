package in.dexter.identity.app;

import in.dexter.contracts.events.identity.SecurityAlertV1;
import in.dexter.contracts.events.identity.SessionRevokedV1;
import in.dexter.core.error.DexterException;
import in.dexter.core.id.Ids;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.OutboxWriter;
import in.dexter.identity.app.AccountStore.Account;
import in.dexter.identity.app.PreAuthStore.LoginRow;
import in.dexter.identity.app.PreAuthStore.RefreshRef;
import in.dexter.identity.app.RefreshTokenStore.LockedRefresh;
import in.dexter.identity.app.RefreshTokenStore.NewRefresh;
import in.dexter.identity.app.SessionStore.NewSession;
import in.dexter.identity.app.SessionStore.SessionRow;
import in.dexter.identity.domain.AccountStatus;
import in.dexter.identity.domain.Emails;
import in.dexter.identity.domain.IdentityErrors;
import in.dexter.identity.domain.IdentityTenants;
import in.dexter.identity.domain.RefreshRotation;
import in.dexter.identity.domain.RevokeReason;
import in.dexter.identity.domain.SecretTokens;
import in.dexter.security.AuthContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sign-in, token refresh and sign-out (MASTER_PLAN 7.1).
 *
 * <p>Refresh: opaque 256-bit tokens, only SHA-256 stored; rotated on every use (30-day sliding window, 90-day
 * absolute); a token presented a second time revokes its whole family (= the session), raises
 * {@code identity.security.alert} and answers 401 {@code REFRESH_TOKEN_REUSED}. The revocation commits before the
 * error is thrown.
 */
public final class AuthService {
  private static final Logger log = LoggerFactory.getLogger(AuthService.class);
  static final List<String> AMR_PASSWORD = List.of("pwd");

  private final TenantTx tx;
  private final PreAuthStore preAuth;
  private final AccountStore accounts;
  private final SessionStore sessions;
  private final RefreshTokenStore refreshTokens;
  private final PasswordHasher hasher;
  private final AccessTokens accessTokens;
  private final OutboxWriter outbox;
  private final IdentitySettings settings;
  private final Clock clock;

  public AuthService(TenantTx tx, PreAuthStore preAuth, AccountStore accounts, SessionStore sessions,
      RefreshTokenStore refreshTokens, PasswordHasher hasher, AccessTokens accessTokens, OutboxWriter outbox,
      IdentitySettings settings, Clock clock) {
    this.tx = tx;
    this.preAuth = preAuth;
    this.accounts = accounts;
    this.sessions = sessions;
    this.refreshTokens = refreshTokens;
    this.hasher = hasher;
    this.accessTokens = accessTokens;
    this.outbox = outbox;
    this.settings = settings;
    this.clock = clock;
  }

  // ------------------------------------------------------------------------------------------- login

  public TokenPair login(LoginCommand cmd, ClientInfo client) {
    String clientId = Clients.require(settings, cmd.clientId());
    String password = cmd.password() == null ? "" : cmd.password();
    String email = Emails.normalize(cmd.email());
    LoginRow row = email == null ? null : tx.read(IdentityTenants.SYSTEM, () -> preAuth.findLogin(email)).orElse(null);
    if (row == null) {
      hasher.dummyMatch(password); // same cost as a real check: timing does not reveal unknown emails
      throw invalidCredentials();
    }
    if (!hasher.matches(password, row.passwordHash())) {
      throw invalidCredentials();
    }
    AccountStatus status = AccountStatus.fromDb(row.status());
    if (status == AccountStatus.PENDING_VERIFICATION) {
      throw new DexterException(IdentityErrors.EMAIL_NOT_VERIFIED, "Verify your email address first; check your inbox");
    }
    if (!status.canSignIn()) {
      throw new DexterException(IdentityErrors.ACCOUNT_UNAVAILABLE, "This account cannot sign in");
    }
    String rehash = hasher.needsRehash(row.passwordHash()) ? hasher.hash(password) : null;
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    Instant authTime = now.truncatedTo(ChronoUnit.SECONDS);
    UUID sessionId = Ids.newId();
    String refresh = SecretTokens.newToken();
    Instant absolute = now.plus(settings.refreshAbsolute());
    Instant refreshExpires = RefreshRotation.nextExpiry(now, settings.refreshIdle(), absolute);
    Tenant tenant = Tenant.user(row.accountId(), zone(row.tz()));
    tx.writeVoid(tenant, () -> {
      if (rehash != null) {
        accounts.updatePasswordHash(row.accountId(), rehash, false);
      }
      sessions.create(new NewSession(sessionId, row.accountId(), clientId, label(cmd.deviceLabel()),
          uaHash(client.userAgent()), client.ip(), AMR_PASSWORD, authTime, now));
      refreshTokens.insert(new NewRefresh(Ids.newId(), row.accountId(), sessionId, Ids.newId(),
          SecretTokens.hash(refresh), null, now, refreshExpires, absolute));
    });
    if (rehash != null) {
      log.info("PASSWORD_REHASHED account={}", row.accountId());
    }
    AccessTokens.Issued at = accessTokens.forUser(row.accountId(), sessionId, clientId, AMR_PASSWORD, authTime,
        row.tz(), row.tier(), row.mfaEnabled());
    return new TokenPair(at.token(), "Bearer", at.expiresIn(), refresh, refreshExpires, sessionId);
  }

  // ------------------------------------------------------------------------------------------- refresh

  private sealed interface RefreshResult permits Rotated, Rejected {}

  private record Rotated(Account account, SessionRow session, String refresh, Instant refreshExpires)
      implements RefreshResult {}

  private record Rejected(DexterException error) implements RefreshResult {}

  public TokenPair refresh(String refreshToken) {
    if (!SecretTokens.looksValid(refreshToken)) {
      throw invalidRefresh();
    }
    byte[] hash = SecretTokens.hash(refreshToken);
    RefreshRef ref = tx.read(IdentityTenants.SYSTEM, () -> preAuth.findRefresh(hash)).orElseThrow(AuthService::invalidRefresh);
    Tenant tenant = Tenant.user(ref.accountId());
    RefreshResult result = tx.write(tenant, () -> rotate(ref));
    if (result instanceof Rejected r) {
      throw r.error();
    }
    Rotated ok = (Rotated) result;
    AccessTokens.Issued at = accessTokens.forUser(ok.account().id(), ok.session().id(), ok.session().clientId(),
        ok.session().amr(), ok.session().authTime(), ok.account().tz(), ok.account().tier(), false);
    return new TokenPair(at.token(), "Bearer", at.expiresIn(), ok.refresh(), ok.refreshExpires(), ok.session().id());
  }

  private RefreshResult rotate(RefreshRef ref) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    LockedRefresh t = refreshTokens.lock(ref.accountId(), ref.tokenId()).orElse(null);
    if (t == null) {
      return new Rejected(invalidRefresh());
    }
    RefreshRotation.Outcome outcome = RefreshRotation.decide(new RefreshRotation.State(t.expiresAt(),
        t.absoluteExpiresAt(), t.rotatedAt(), t.revokedAt(), t.sessionRevoked()), now);
    switch (outcome) {
      case REUSED -> {
        refreshTokens.revokeFamily(ref.accountId(), t.familyId(), now);
        if (sessions.revoke(ref.accountId(), t.sessionId(), RevokeReason.REFRESH_REUSE, now)) {
          sessionRevoked(ref.accountId(), t.sessionId(), RevokeReason.REFRESH_REUSE, now);
        }
        outbox.append(IdentityEvents.SECURITY_ALERT, ref.accountId(), ref.accountId(), 1,
            new SecurityAlertV1(SecurityAlertV1.REFRESH_TOKEN_REUSE, t.sessionId(), now.toString()));
        log.warn("REFRESH_TOKEN_REUSE account={} session={} family={}", ref.accountId(), t.sessionId(), t.familyId());
        return new Rejected(new DexterException(IdentityErrors.REFRESH_TOKEN_REUSED,
            "This refresh token was already used; the session has been signed out for safety"));
      }
      case EXPIRED, REVOKED -> {
        return new Rejected(invalidRefresh());
      }
      case ROTATE -> {
        Account account = accounts.find(ref.accountId()).orElse(null);
        SessionRow session = sessions.find(ref.accountId(), t.sessionId()).orElse(null);
        if (account == null || session == null || !AccountStatus.fromDb(account.status()).canSignIn()) {
          return new Rejected(invalidRefresh());
        }
        String next = SecretTokens.newToken();
        Instant expires = RefreshRotation.nextExpiry(now, settings.refreshIdle(), t.absoluteExpiresAt());
        refreshTokens.markRotated(ref.accountId(), t.id(), now);
        refreshTokens.insert(new NewRefresh(Ids.newId(), ref.accountId(), t.sessionId(), t.familyId(),
            SecretTokens.hash(next), t.id(), now, expires, t.absoluteExpiresAt()));
        sessions.touch(ref.accountId(), t.sessionId(), now);
        return new Rotated(account, session, next, expires);
      }
      default -> throw new IllegalStateException("unknown outcome " + outcome);
    }
  }

  // ------------------------------------------------------------------------------------------- logout

  /** Signs out the session of this access token (and its refresh tokens). Idempotent. */
  public void logout(AuthContext caller) {
    UUID sid = UUID.fromString(caller.sessionId());
    Instant now = clock.instant();
    tx.writeVoid(caller.tenant(), () -> revokeSessions(caller.accountId(), List.of(sid), RevokeReason.LOGOUT, now));
  }

  /** Signs out every session of the account, this one included (step-up on the controller). */
  public int logoutAll(AuthContext caller) {
    Instant now = clock.instant();
    return tx.write(caller.tenant(), () -> {
      List<UUID> revoked = sessions.revokeAll(caller.accountId(), null, RevokeReason.LOGOUT_ALL, now);
      refreshTokens.revokeSessions(caller.accountId(), revoked, now);
      revoked.forEach(s -> sessionRevoked(caller.accountId(), s, RevokeReason.LOGOUT_ALL, now));
      return revoked.size();
    });
  }

  /** Revokes the given open sessions + their refresh tokens and emits one event each. Inside a tenant tx. */
  void revokeSessions(UUID accountId, Collection<UUID> sessionIds, RevokeReason reason, Instant now) {
    for (UUID s : sessionIds) {
      if (sessions.revoke(accountId, s, reason, now)) {
        refreshTokens.revokeSessions(accountId, List.of(s), now);
        sessionRevoked(accountId, s, reason, now);
      }
    }
  }

  void sessionRevoked(UUID accountId, UUID sessionId, RevokeReason reason, Instant now) {
    outbox.append(IdentityEvents.SESSION_REVOKED, accountId, sessionId, 1,
        new SessionRevokedV1(sessionId, reason.db(), now.toString()));
  }

  // ------------------------------------------------------------------------------------------- helpers

  static DexterException invalidCredentials() {
    return new DexterException(IdentityErrors.INVALID_CREDENTIALS, "The email or password is incorrect");
  }

  static DexterException invalidRefresh() {
    return new DexterException(IdentityErrors.INVALID_REFRESH_TOKEN, "Sign in again");
  }

  private static ZoneId zone(String tz) {
    try {
      return ZoneId.of(tz);
    } catch (RuntimeException e) {
      return in.dexter.core.time.Clocks.DEFAULT_ZONE;
    }
  }

  private static String label(String deviceLabel) {
    if (deviceLabel == null || deviceLabel.isBlank()) {
      return null;
    }
    String s = deviceLabel.strip().replaceAll("\\p{Cntrl}", "");
    return s.length() > 80 ? s.substring(0, 80) : s;
  }

  private static byte[] uaHash(String userAgent) {
    return userAgent == null || userAgent.isBlank() ? null
        : SecretTokens.sha256(userAgent.getBytes(StandardCharsets.UTF_8));
  }
}
