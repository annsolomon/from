package in.dexter.security;

import in.dexter.core.error.ForbiddenException;
import in.dexter.core.tenant.Tenant;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The verified caller of one request (MASTER_PLAN 7.1/7.2). Built only by {@code JwtVerifier} from a
 * signed access token; business code never builds one from a header, path or body.
 *
 * <p>{@link #accountId()} is set for {@link Kind#USER} and {@link Kind#ADMIN} and null for
 * {@link Kind#SERVICE}. {@link #service()} is the calling service's name for service tokens
 * ({@code sub = "svc:<name>"}) and null otherwise.
 */
public record AuthContext(
    Kind kind,
    String subject,
    @Nullable UUID accountId,
    @Nullable String service,
    @Nullable String sessionId,
    String clientId,
    Set<String> audiences,
    Set<String> scopes,
    Set<String> roles,
    Set<String> permissions,
    Set<String> amr,
    @Nullable Instant authTime,
    boolean mfaEnrolled,
    ZoneId tz,
    String tier,
    String tokenId,
    Instant issuedAt,
    Instant expiresAt) {

  /** Who is calling. */
  public enum Kind {
    /** An end user acting on their own account. */
    USER,
    /** An account holding at least one admin role ({@link #ADMIN_ROLES}). */
    ADMIN,
    /** Another Dexter service (client-credentials token). */
    SERVICE
  }

  /** Roles that make an account an admin (MASTER_PLAN 7.2). */
  public static final Set<String> ADMIN_ROLES =
      Set.of(Tenant.SUPPORT, Tenant.CATALOG_EDITOR, Tenant.FINANCE, Tenant.SUPER_ADMIN);
  /** The {@code amr} value identity sets after a second factor. */
  public static final String AMR_MFA = "mfa";

  public AuthContext {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(clientId, "clientId");
    Objects.requireNonNull(tz, "tz");
    Objects.requireNonNull(tier, "tier");
    Objects.requireNonNull(tokenId, "tokenId");
    Objects.requireNonNull(issuedAt, "issuedAt");
    Objects.requireNonNull(expiresAt, "expiresAt");
    if (kind == Kind.SERVICE ? (accountId != null || service == null) : (accountId == null || service != null)) {
      throw new IllegalArgumentException("kind " + kind + " does not match accountId/service");
    }
    audiences = Set.copyOf(audiences);
    scopes = Set.copyOf(scopes);
    roles = Set.copyOf(roles);
    permissions = Set.copyOf(permissions);
    amr = Set.copyOf(amr);
  }

  public boolean isService() {
    return kind == Kind.SERVICE;
  }

  public boolean hasPermission(String permission) {
    return permissions.contains(permission);
  }

  public boolean hasRole(String role) {
    return roles.contains(role);
  }

  /**
   * The RLS tenant for acting as the account holder (role {@code USER}). Admins acting as admins use
   * {@link #tenantAs(String)} so the admin role is chosen explicitly (least privilege).
   */
  public Tenant tenant() {
    if (accountId == null) {
      throw new ForbiddenException("A service token cannot act as an account");
    }
    return Tenant.user(accountId, tz);
  }

  /** The RLS tenant with an admin role this caller holds. */
  public Tenant tenantAs(String role) {
    if (accountId == null || !roles.contains(role)) {
      throw new ForbiddenException("Caller does not hold role " + role);
    }
    return new Tenant(accountId, role, tz);
  }

  /** No token material, no PII beyond the account id (logged ids are fine, MASTER_PLAN 7.4). */
  @Override
  public String toString() {
    return "AuthContext[" + kind + "," + (accountId != null ? accountId : "svc:" + service) + ",jti=" + tokenId + "]";
  }
}
