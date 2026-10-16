package in.dexter.security.jwt;

/**
 * The access-token contract between identity (P04, the only issuer) and every service
 * (MASTER_PLAN 7.1, amendment A-18). Header: {@code alg=ES256}, {@code typ=at+jwt} (RFC 9068),
 * {@code kid} from identity's JWKS; no {@code jku}/{@code jwk}/{@code x5u}/{@code x5c}.
 */
public final class TokenClaims {
  private TokenClaims() {}

  /** Subject prefix of client-credentials tokens: {@code svc:<service-name>}. */
  public static final String SERVICE_SUBJECT_PREFIX = "svc:";

  public static final String SESSION_ID = "sid";
  public static final String CLIENT_ID = "client_id";
  /** Space-separated OAuth scopes. */
  public static final String SCOPE = "scope";
  /** Account roles ({@code USER} and admin roles). */
  public static final String ROLES = "roles";
  /** Fine-grained admin permissions, resolved by identity from the roles (P05). */
  public static final String PERMISSIONS = "perms";
  /** Authentication methods (RFC 8176), for example {@code pwd}, {@code mfa}. */
  public static final String AMR = "amr";
  /** When the user last actively authenticated (seconds). Survives refresh. */
  public static final String AUTH_TIME = "auth_time";
  /** True when the account has MFA enrolled; step-up then also needs {@code amr} to contain {@code mfa}. */
  public static final String MFA_ENROLLED = "mfa_enrolled";
  /** IANA time zone of the account. */
  public static final String TZ = "tz";
  /** Subscription tier (billing, P16). */
  public static final String TIER = "tier";
}
