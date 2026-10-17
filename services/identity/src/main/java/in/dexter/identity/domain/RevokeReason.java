package in.dexter.identity.domain;

import java.util.Locale;

/** {@code identity.sessions.revoke_reason} and the {@code reason} of {@code identity.session.revoked}. */
public enum RevokeReason {
  LOGOUT, LOGOUT_ALL, USER_REVOKED, PASSWORD_CHANGE, PASSWORD_RESET, REFRESH_REUSE, ADMIN;

  public String db() {
    return name().toLowerCase(Locale.ROOT);
  }
}
