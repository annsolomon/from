package in.dexter.identity.app;

import in.dexter.contracts.events.identity.AdminActionV1;
import in.dexter.core.error.DexterException;
import in.dexter.core.error.ValidationException;
import in.dexter.core.id.Ids;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.OutboxWriter;
import in.dexter.identity.app.AdminStore.Admin;
import in.dexter.identity.app.AdminStore.AdminLogin;
import in.dexter.identity.app.AdminStore.Elevation;
import in.dexter.identity.app.AdminStore.LockedRefresh;
import in.dexter.identity.app.AdminStore.Session;
import in.dexter.identity.app.PreAuthStore.RefreshRef;
import in.dexter.identity.domain.Emails;
import in.dexter.identity.domain.IdentityErrors;
import in.dexter.identity.domain.IdentityTenants;
import in.dexter.identity.domain.PasswordPolicy;
import in.dexter.identity.domain.RefreshRotation;
import in.dexter.identity.domain.SecretTokens;
import in.dexter.identity.domain.Totp;
import in.dexter.security.AuthContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Admin console sign-in (MASTER_PLAN P05, 7.2): password, then a mandatory TOTP — an admin without one must enrol
 * it before any token is issued. Admin tokens carry the standing roles, SUPER_ADMIN while a break-glass elevation
 * is active, and the permissions those roles resolve to. Everything runs as identity_app with the admin's own id
 * as tenant (admins acting on themselves).
 */
public final class AdminAuthService {
  private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);
  static final String ROLE = "ADMIN";
  static final List<String> AMR = List.of("pwd", "mfa", "otp");

  /** Password accepted: finish with the code ({@code setup}: enrol first via /mfa/setup). */
  public record Challenge(String mfaToken, boolean setup, long expiresIn) {}

  public record Setup(String secret, String otpauthUri, int digits, int period) {}

  /** Admin tokens (same shape as user tokens). */
  public record Issued(String accessToken, long expiresIn, String refreshToken, Instant refreshExpiresAt,
      UUID sessionId, List<String> roles, Instant elevatedUntil) {}

  private final TenantTx tx;
  private final AdminStore store;
  private final PasswordHasher hasher;
  private final PasswordPolicy.BreachedList breached;
  private final SecretSealer sealer;
  private final MfaChallenges challenges;
  private final Throttles throttles;
  private final AccessTokens tokens;
  private final OutboxWriter outbox;
  private final SecuritySettings security;
  private final Clock clock;

  public AdminAuthService(TenantTx tx, AdminStore store, PasswordHasher hasher, PasswordPolicy.BreachedList breached,
      SecretSealer sealer, MfaChallenges challenges, Throttles throttles, AccessTokens tokens, OutboxWriter outbox,
      SecuritySettings security, Clock clock) {
    this.tx = tx;
    this.store = store;
    this.hasher = hasher;
    this.breached = breached;
    this.sealer = sealer;
    this.challenges = challenges;
    this.throttles = throttles;
    this.tokens = tokens;
    this.outbox = outbox;
    this.security = security;
    this.clock = clock;
  }

  static Tenant tenant(UUID adminId) {
    return new Tenant(adminId, ROLE, java.time.ZoneOffset.UTC);
  }

  // ------------------------------------------------------------------------------------------- sign-in

  public Challenge login(String rawEmail, String password, ClientInfo client) {
    Instant attempt = clock.instant();
    String key = Throttles.adminLogin(rawEmail);
    throttles.check(attempt, key, security.loginPolicy(), client.ip());
    String email = Emails.normalize(rawEmail);
    AdminLogin row = email == null ? null : tx.read(IdentityTenants.SYSTEM, () -> store.findLogin(email)).orElse(null);
    String pw = password == null ? "" : password;
    boolean ok;
    if (row == null || row.passwordHash() == null) {
      hasher.dummyMatch(pw);
      ok = false;
    } else {
      ok = hasher.matches(pw, row.passwordHash());
    }
    if (!ok || !"active".equals(row.status())) {
      if (throttles.fail(attempt, key, security.loginPolicy(), client.ip()) && row != null) {
        action(row.adminId(), "ADMIN_LOCKED", "admin", row.adminId(), "", null);
      }
      throw AuthService.invalidCredentials();
    }
    throttles.succeed(key);
    if (hasher.needsRehash(row.passwordHash())) {
      String rehash = hasher.hash(pw);
      tx.writeVoid(tenant(row.adminId()), () -> store.updatePasswordHash(row.adminId(), rehash));
    }
    String token = challenges.issue(new MfaChallenges.Challenge(MfaChallenges.Kind.ADMIN, row.adminId(),
        security.adminClientId(), null, client.ip(), uaHash(client.userAgent()), !row.mfaConfirmed()),
        security.mfaChallengeTtl());
    return new Challenge(token, !row.mfaConfirmed(), security.mfaChallengeTtl().toSeconds());
  }

  /** First sign-in of an admin without TOTP: a (pending) secret for the authenticator app. */
  public Setup setup(String mfaToken) {
    MfaChallenges.Challenge c = adminChallenge(mfaToken);
    if (!c.setup()) {
      throw new DexterException(IdentityErrors.MFA_ALREADY_ENROLLED, "This admin already has an authenticator");
    }
    byte[] secret = Totp.newSecret();
    return tx.write(tenant(c.principalId()), () -> {
      Admin a = store.find(c.principalId()).orElseThrow(AuthService::challengeInvalid);
      if (store.totp(a.id()).filter(t -> t.confirmedAt() != null).isPresent()) {
        throw new DexterException(IdentityErrors.MFA_ALREADY_ENROLLED, "This admin already has an authenticator");
      }
      SecretSealer.Sealed sealed = sealer.seal(aad(a.id()), secret);
      store.putPendingTotp(a.id(), sealed.bytes(), sealed.kekId());
      return new Setup(Totp.Base32.encode(secret), Totp.otpauthUri(security.totpIssuer() + " Admin", a.email(), secret),
          Totp.DIGITS, Totp.PERIOD_SECONDS);
    });
  }

  /** The TOTP code: confirms a pending setup or checks the enrolled secret (replay-guarded), then issues tokens. */
  public Issued verify(String mfaToken, String code) {
    MfaChallenges.Challenge c = adminChallenge(mfaToken);
    UUID adminId = c.principalId();
    Instant attempt = clock.instant();
    String key = Throttles.adminMfa(adminId);
    throttles.check(attempt, key, security.mfaPolicy(), c.ip());
    boolean valid = tx.write(tenant(adminId), () -> {
      AdminStore.Totp t = store.totp(adminId).orElse(null);
      if (t == null || (t.confirmedAt() == null) != c.setup()) {
        return false;
      }
      byte[] secret = sealer.open(t.kekId(), aad(adminId), t.secretEnc());
      long step = Totp.verify(secret, code == null ? "" : code.strip(), attempt, security.totpWindow());
      if (step < 0) {
        return false;
      }
      if (c.setup()) {
        store.confirmTotp(adminId, step, attempt);
        return true;
      }
      return store.advanceTotpStep(adminId, step);
    });
    if (!valid) {
      throttles.fail(attempt, key, security.mfaPolicy(), c.ip());
      if (challenges.fail(mfaToken, security.mfaChallengeTtl()) >= security.mfaMaxAttempts()) {
        challenges.consume(mfaToken);
        throw AuthService.challengeInvalid();
      }
      throw new DexterException(IdentityErrors.INVALID_MFA_CODE, "The code is not valid (or was already used)");
    }
    if (challenges.consume(mfaToken).isEmpty()) {
      throw AuthService.challengeInvalid();
    }
    throttles.succeed(key);
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    Instant authTime = now.truncatedTo(ChronoUnit.SECONDS);
    UUID sessionId = Ids.newId();
    String refresh = SecretTokens.newToken();
    Instant absolute = now.plus(security.adminRefreshAbsolute());
    Instant refreshExpires = RefreshRotation.nextExpiry(now, security.adminRefreshIdle(), absolute);
    byte[] ua = c.uaHashB64() == null ? null : Base64.getDecoder().decode(c.uaHashB64());
    Grant g = tx.write(tenant(adminId), () -> {
      Admin a = store.find(adminId).filter(x -> "active".equals(x.status())).orElseThrow(AuthService::challengeInvalid);
      store.createSession(sessionId, adminId, c.clientId(), ua, c.ip(), AMR, authTime, now);
      store.insertRefresh(Ids.newId(), adminId, sessionId, Ids.newId(), SecretTokens.hash(refresh), null, now,
          refreshExpires, absolute);
      store.audit(adminId, "ADMIN_SIGNED_IN", "session", sessionId, "{}");
      appendAction(adminId, "ADMIN_SIGNED_IN", "session", sessionId, "", null, now);
      return grant(a.id(), now);
    });
    AccessTokens.Issued at = tokens.forAdmin(adminId, sessionId, c.clientId(), g.roles(), g.permissions(), AMR,
        authTime, g.until());
    log.info("ADMIN_SIGNED_IN admin={} session={} roles={}", adminId, sessionId, g.roles());
    return new Issued(at.token(), at.expiresIn(), refresh, refreshExpires, sessionId, g.roles(), g.until());
  }

  // ------------------------------------------------------------------------------------------- refresh / logout

  private sealed interface Outcome permits Rotated, Rejected {}

  private record Rotated(Session session, String refresh, Instant expires, Grant grant) implements Outcome {}

  private record Rejected(DexterException error) implements Outcome {}

  public Issued refresh(String refreshToken) {
    if (!SecretTokens.looksValid(refreshToken)) {
      throw AuthService.invalidRefresh();
    }
    RefreshRef ref = tx.read(IdentityTenants.SYSTEM, () -> store.findRefresh(SecretTokens.hash(refreshToken)))
        .orElseThrow(AuthService::invalidRefresh);
    UUID adminId = ref.accountId();
    Outcome o = tx.write(tenant(adminId), () -> {
      Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
      LockedRefresh t = store.lockRefresh(adminId, ref.tokenId()).orElse(null);
      if (t == null) {
        return new Rejected(AuthService.invalidRefresh());
      }
      switch (RefreshRotation.decide(new RefreshRotation.State(t.expiresAt(), t.absoluteExpiresAt(), t.rotatedAt(),
          t.revokedAt(), t.sessionRevoked()), now)) {
        case REUSED -> {
          store.revokeFamily(adminId, t.familyId(), now);
          store.revokeSession(adminId, t.sessionId(), "refresh_reuse", now);
          store.audit(adminId, "REFRESH_TOKEN_REUSE", "session", t.sessionId(), "{}");
          log.warn("ADMIN_REFRESH_TOKEN_REUSE admin={} session={}", adminId, t.sessionId());
          return new Rejected(new DexterException(IdentityErrors.REFRESH_TOKEN_REUSED,
              "This refresh token was already used; the session has been signed out for safety"));
        }
        case EXPIRED, REVOKED -> {
          return new Rejected(AuthService.invalidRefresh());
        }
        default -> {
          Admin a = store.find(adminId).orElse(null);
          Session s = store.session(adminId, t.sessionId()).orElse(null);
          if (a == null || s == null || !"active".equals(a.status())) {
            return new Rejected(AuthService.invalidRefresh());
          }
          String next = SecretTokens.newToken();
          Instant expires = RefreshRotation.nextExpiry(now, security.adminRefreshIdle(), t.absoluteExpiresAt());
          store.markRotated(adminId, t.id(), now);
          store.insertRefresh(Ids.newId(), adminId, t.sessionId(), t.familyId(), SecretTokens.hash(next), t.id(), now,
              expires, t.absoluteExpiresAt());
          store.touchSession(adminId, t.sessionId(), now);
          return new Rotated(s, next, expires, grant(adminId, now));
        }
      }
    });
    if (o instanceof Rejected r) {
      throw r.error();
    }
    Rotated ok = (Rotated) o;
    AccessTokens.Issued at = tokens.forAdmin(adminId, ok.session().id(), ok.session().clientId(), ok.grant().roles(),
        ok.grant().permissions(), ok.session().amr(), ok.session().authTime(), ok.grant().until());
    return new Issued(at.token(), at.expiresIn(), ok.refresh(), ok.expires(), ok.session().id(), ok.grant().roles(),
        ok.grant().until());
  }

  public void logout(AuthContext caller) {
    AdminGuard.require(caller);
    UUID sid = UUID.fromString(caller.sessionId());
    Instant now = clock.instant();
    tx.writeVoid(tenant(caller.accountId()), () -> {
      if (store.revokeSession(caller.accountId(), sid, "logout", now)) {
        store.revokeSessionTokens(caller.accountId(), sid, now);
      }
    });
  }

  /** A fresh admin access token for the caller's session (after elevation start/end). */
  AccessTokens.Issued reissue(AuthContext caller) {
    UUID adminId = caller.accountId();
    UUID sid = UUID.fromString(caller.sessionId());
    Instant now = clock.instant();
    record Pair(Session s, Grant g) {}
    Pair p = tx.read(tenant(adminId), () -> new Pair(store.session(adminId, sid).filter(x -> x.revokedAt() == null)
        .orElseThrow(AuthService::invalidRefresh), grant(adminId, now)));
    return tokens.forAdmin(adminId, sid, p.s().clientId(), p.g().roles(), p.g().permissions(), p.s().amr(),
        p.s().authTime(), p.g().until());
  }

  // ------------------------------------------------------------------------------------------- invitation

  /** POST /v1/admin/auth/invite/accept: single-use link from the invitation email → password set. */
  public void acceptInvite(String token, String password) {
    List<in.dexter.core.error.FieldError> errors = PasswordPolicy.check("password", password, null, breached);
    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
    if (!SecretTokens.looksValid(token)) {
      throw EmailVerificationService.invalid();
    }
    String hash = hasher.hash(password);
    Instant now = clock.instant();
    tx.writeVoid(IdentityTenants.SYSTEM, () -> {
      AdminStore.Invite inv = store.acceptInvite(SecretTokens.hash(token), hash)
          .orElseThrow(EmailVerificationService::invalid);
      appendAction(inv.adminId(), "INVITE_ACCEPTED", "admin", inv.adminId(), "", null, now);
    });
  }

  // ------------------------------------------------------------------------------------------- helpers

  /** Roles (standing + SUPER_ADMIN while elevated), permissions, and the elevation end that caps the token. */
  record Grant(List<String> roles, List<String> permissions, Instant until) {}

  Grant grant(UUID adminId, Instant now) {
    Set<String> roles = new TreeSet<>(store.roles(adminId));
    Elevation e = store.activeElevation(adminId, now).orElse(null);
    if (e != null) {
      roles.add(in.dexter.core.tenant.Tenant.SUPER_ADMIN);
    }
    List<String> perms = new ArrayList<>(new TreeSet<>(store.permissions(roles)));
    return new Grant(List.copyOf(roles), perms, e == null ? null : e.expiresAt());
  }

  private MfaChallenges.Challenge adminChallenge(String mfaToken) {
    return challenges.peek(mfaToken == null ? "" : mfaToken).filter(x -> x.kind() == MfaChallenges.Kind.ADMIN)
        .orElseThrow(AuthService::challengeInvalid);
  }

  static String aad(UUID adminId) {
    return "admin-totp:" + adminId;
  }

  /** Outbox event in its own transaction (used where no transaction is open). */
  void action(UUID adminId, String action, String targetType, UUID targetId, String reason, UUID accountId) {
    tx.writeVoid(IdentityTenants.SYSTEM, () -> appendAction(adminId, action, targetType, targetId, reason, accountId,
        clock.instant()));
  }

  void appendAction(UUID adminId, String action, String targetType, UUID targetId, String reason, UUID accountId,
      Instant now) {
    outbox.append(IdentityEvents.ADMIN_ACTION, accountId, adminId, 1,
        new AdminActionV1(action, adminId, targetType, targetId, reason == null ? "" : reason, now.toString()));
  }

  private static String uaHash(String ua) {
    return ua == null || ua.isBlank() ? null
        : Base64.getEncoder().encodeToString(SecretTokens.sha256(ua.getBytes(StandardCharsets.UTF_8)));
  }
}
