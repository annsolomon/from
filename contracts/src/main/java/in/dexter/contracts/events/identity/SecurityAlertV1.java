package in.dexter.contracts.events.identity;

import java.util.UUID;

/**
 * {@code identity.security.alert} v1. {@code kind}: {@code REFRESH_TOKEN_REUSE} (P04; P05 adds lockout kinds in a
 * new schema version). {@code refId}: the affected session for {@code REFRESH_TOKEN_REUSE}.
 */
public record SecurityAlertV1(String kind, UUID refId, String occurredAt) {
  public static final String TYPE = "identity.security.alert";
  public static final int VERSION = 1;
  public static final String REFRESH_TOKEN_REUSE = "REFRESH_TOKEN_REUSE";
}
