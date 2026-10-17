package in.dexter.identity.app;

import in.dexter.contracts.events.identity.SecurityAlertV2;
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
  private final SecuritySettings security;
  private final Throttles throttles;
  private final MfaChallenges challenges;
  private final MfaStore mfaStore;
  private final UserSecondFactor secondFactor;
  private final Clock clock;

  public AuthService(TenantTx tx, PreAuthStore preAuth, AccountStore accounts, SessionStore sessions,
      RefreshTokenStore refreshTokens, PasswordHasher hasher, AccessTokens accessTokens, OutboxWriter outbox,
      IdentitySettings settings, SecuritySettings security, Throttles throttles, MfaChallenges challenges,
      MfaStore mfaStore, UserSecondFactor secondFactor, Clock clock) {
    this.tx = tx;
    this.preAuth = preAuth;
    this.accounts = accounts;
    this.sessions = sessions;
    this.refreshTokens = refreshTokens;
    this.hasher = hasher;
    this.accessTokens = accessTokens;
    this.outbox = outbox;
    this.settings = settings;
    this.security = security;
    this.throttles = throttles;
    this.challenges = challenges;
    this.mfaStore = mfaStore;
    this.secondFactor = secondFactor;
    this.clock = clock;
  }

  // ------------------------------------------------------------------------------------------- login

  /** Login answer: tokens, or (MFA enrolled) a challenge to complete at /v1/auth/mfa/verify. */
  public sealed interface LoginResult permits Tokens, MfaRequired {}

  public record Tokens(TokenPair pair) implements LoginResult {}

  public record MfaRequired(String mfaToken, long expiresIn) implements LoginResult {}

  /**
   * Throttled (MASTER_PLAN 7.1): per email (hashed, also for unknown emails) and per IP, progressive delay then a
   * 15-minute lock. A lock of a real account raises {@code ACCOUNT_LOCKED}.
   */
  public LoginResult login(LoginCommand cmd, ClientInfo client) {
    String clientId = Clients.require(settings, cmd.clientId());
    String password = cmd.password() == null ? "" : cmd.password();
    String key = Throttles.userLogin(cmd.email());
    Instant attempt = clock.instant();
    throttles.check(attempt, key, security.loginPolicy(), client.ip());
    String email = Emails.normalize(cmd.email());
    LoginRow row = email == null ? null : tx.read(IdentityTenants.SYSTEM, () -> preAuth.findLogin(email)).orElse(null);
    if (row == null) {
      hasher.dummyMatch(password); // same cost as a real check: timing does not reveal unknown emails
      throttles.fail(attempt, key, security.loginPolicy(), client.ip());
      throw invalidCredentials();
    }
    if (!hasher.matches(password, row.passwordHash())) {
      if (throttles.fail(attempt, key, security.loginPolicy(), client.ip())) {
        alert(row.accountId(), SecurityAlertV2.ACCOUNT_LOCKED, row.accountId());
      }
      throw invalidCredentials();
    }
    throttles.succeed(key);
    AccountStatus status = AccountStatus.fromDb(row.status());
    if (status == AccountStatus.PENDING_VERIFICATION) {
      throw new DexterException(IdentityErrors.EMAIL_NOT_VERIFIED, "Verify your email address first; check your inbox");
    }
    if (!status.canSignIn()) {
      throw new DexterException(IdentityErrors.ACCOUNT_UNAVAILABLE, "This account cannot sign in");
    }
    String rehash = hasher.needsRehash(row.passwordHash()) ? hasher.hash(password) : null;
    if (rehash != null) {
      tx.writeVoid(Tenant.user(row.accountId()), () -> accounts.updatePasswordHash(row.accountId(), rehash, false));
      log.info("PASSWORD_REHASHED account={}", row.accountId());
    }
    if (row.mfaEnabled()) {
      String token = challenges.issue(new MfaChallenges.Challenge(MfaChallenges.Kind.USER, row.accountId(), clientId,
          label(cmd.deviceLabel()), client.ip(), b64(uaHash(client.userAgent())), false), security.mfaChallengeTtl());
      return new MfaRequired(token, security.mfaChallengeTtl().toSeconds());
    }
    return new Tokens(openSession(row.accountId(), row.tz(), row.tier(), false, clientId, label(cmd.deviceLabel()),
        uaHash(client.userAgent()), client.ip(), AMR_PASSWORD));
  }

  /**
   * Second step of an MFA sign-in: a TOTP code (or a recovery code) for the challenge. 5 wrong codes burn the
   * challenge; failures also count on the account's MFA throttle.
   */
  public TokenPair verifyMfa(String mfaToken, String code, String recoveryCode) {
    MfaChallenges.Challenge c = challenges.peek(mfaToken).filter(x -> x.kind() == MfaChallenges.Kind.USER)
        .orElseThrow(AuthService::challengeInvalid);
    Instant attempt = clock.instant();
    String key = Throttles.userMfa(c.principalId());
    throttles.check(attempt, key, security.mfaPolicy(), c.ip());
    Tenant tenant = Tenant.user(c.principalId());
    List<String> amr = tx.write(tenant, () -> secondFactor.verify(c.principalId(), code, recoveryCode));
    if (amr == null) {
      throttles.fail(attempt, key, security.mfaPolicy(), c.ip());
      if (challenges.fail(mfaToken, security.mfaChallengeTtl()) >= security.mfaMaxAttempts()) {
        challenges.consume(mfaToken);
        throw challengeInvalid();
      }
      throw new DexterException(IdentityErrors.INVALID_MFA_CODE, "The code is not valid (or was already used)");
    }
    if (challenges.consume(mfaToken).isEmpty()) {
      throw challengeInvalid(); // a concurrent request completed this challenge first
    }
    throttles.succeed(key);
    Account a = tx.read(tenant, () -> accounts.find(c.principalId())).orElseThrow(AuthService::challengeInvalid);
    if (!AccountStatus.fromDb(a.status()).canSignIn()) {
      throw new DexterException(IdentityErrors.ACCOUNT_UNAVAILABLE, "This account cannot sign in");
    }
    byte[] ua = c.uaHashB64() == null ? null : java.util.Base64.getDecoder().decode(c.uaHashB64());
    return openSession(a.id(), a.tz(), a.tier(), true, c.clientId(), c.deviceLabel(), ua, c.ip(), amr);
  }

  /** Session + first refresh token + access token. */
  private TokenPair openSession(UUID accountId, String tz, String tier, boolean mfaEnrolled, String clientId,
      String deviceLabel, byte[] uaHash, String ip, List<String> amr) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    Instant authTime = now.truncatedTo(ChronoUnit.SECONDS);
    UUID sessionId = Ids.newId();
    String refresh = SecretTokens.newToken();
    Instant absolute = now.plus(settings.refreshAbsolute());
    Instant refreshExpires = RefreshRotation.nextExpiry(now, settings.refreshIdle(), absolute);
    tx.writeVoid(Tenant.user(accountId, zone(tz)), () -> {
      sessions.create(new NewSession(sessionId, accountId, clientId, deviceLabel, uaHash, ip, amr, authTime, now));
      refreshTokens.insert(new NewRefresh(Ids.newId(), accountId, sessionId, Ids.newId(), SecretTokens.hash(refresh),
          null, now, refreshExpires, absolute));
    });
    AccessTokens.Issued at = accessTokens.forUser(accountId, sessionId, clientId, amr, authTime, tz, tier, mfaEnrolled);
    return new TokenPair(at.token(), "Bearer", at.expiresIn(), refresh, refreshExpires, sessionId);
  }

  // ------------------------------------------------------------------------------------------- step-up

  /** A fresh access token after re-authentication. */
  public record StepUp(String accessToken, long expiresIn) {}

  /**
   * POST /v1/auth/step-up (MASTER_PLAN 7.1): password again, plus the second factor when MFA is enrolled. The
   * session's {@code auth_time} and {@code amr} are updated, so refreshed tokens keep the recent sign-in.
   */
  public StepUp stepUp(AuthContext caller, String password, String code, String recoveryCode) {
    UUID accountId = caller.accountId();
    UUID sid = UUID.fromString(caller.sessionId());
    Instant attempt = clock.instant();
    String key = Throttles.stepUp(accountId);
    throttles.check(attempt, key, security.loginPolicy(), null);
    Account a = tx.read(caller.tenant(), () -> accounts.find(accountId)).orElseThrow(AuthService::invalidCredentials);
    boolean mfa = tx.read(caller.tenant(), () -> mfaEnrolled(accountId));
    if (!hasher.matches(password == null ? "" : password, a.passwordHash())) {
      throttles.fail(attempt, key, security.loginPolicy(), null);
      throw invalidCredentials();
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    List<String> amr = tx.write(caller.tenant(), () -> {
      List<String> m = mfa ? secondFactor.verify(accountId, code, recoveryCode) : AMR_PASSWORD;
      if (m != null && !sessions.updateAuth(accountId, sid, now, m)) {
        return List.<String>of(); // session revoked meanwhile
      }
      return m;
    });
    if (amr == null) {
      throttles.fail(attempt, key, security.loginPolicy(), null);
      throw new DexterException(IdentityErrors.INVALID_MFA_CODE, "The verification code is not valid");
    }
    if (amr.isEmpty()) {
      throw invalidRefresh();
    }
    throttles.succeed(key);
    AccessTokens.Issued at = accessTokens.forUser(accountId, sid, caller.clientId(), amr, now, a.tz(), a.tier(), mfa);
    return new StepUp(at.token(), at.expiresIn());
  }

  private boolean mfaEnrolled(UUID accountId) {
    return mfaStore.find(accountId).filter(r -> r.confirmedAt() != null).isPresent();
  }

  private void alert(UUID accountId, String kind, UUID refId) {
    tx.writeVoid(IdentityTenants.SYSTEM, () -> outbox.append(IdentityEvents.SECURITY_ALERT, accountId, accountId, 1,
        new SecurityAlertV2(kind, refId, clock.instant().toString())));
  }

  static DexterException challengeInvalid() {
    return new DexterException(IdentityErrors.MFA_CHALLENGE_INVALID, "Sign in again");
  }

  private static String b64(byte[] b) {
    return b == null ? null : java.util.Base64.getEncoder().encodeToString(b);
  }

  // ------------------------------------------------------------------------------------------- refresh

  private sealed interface RefreshResult permits Rotated, Rejected {}

  private record Rotated(Account account, SessionRow session, String refresh, Instant refreshExpires, boolean mfa)
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
        ok.session().amr(), ok.session().authTime(), ok.account().tz(), ok.account().tier(), ok.mfa());
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
            new SecurityAlertV2(SecurityAlertV2.REFRESH_TOKEN_REUSE, t.sessionId(), now.toString()));
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
        return new Rotated(account, session, next, expires, mfaEnrolled(ref.accountId()));
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
