package in.dexter.contracts.events.identity;

import java.util.UUID;

/**
 * {@code identity.admin.action} v1: an audited admin action. {@code targetId} is the account, admin, elevation or
 * session acted on; {@code reason} is the break-glass reason (empty otherwise).
 */
public record AdminActionV1(String action, UUID actorAdminId, String targetType, UUID targetId, String reason,
    String occurredAt) {
  public static final String TYPE = "identity.admin.action";
  public static final int VERSION = 1;
}
