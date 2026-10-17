package in.dexter.contracts.events.identity;

import java.util.UUID;

/**
 * {@code identity.session.revoked} v1: a session and its refresh tokens were revoked. {@code reason} is one of
 * {@code logout, logout_all, user_revoked, password_change, password_reset, refresh_reuse, admin}.
 */
public record SessionRevokedV1(UUID sessionId, String reason, String revokedAt) {
  public static final String TYPE = "identity.session.revoked";
  public static final int VERSION = 1;
}
