package in.dexter.contracts.events.identity;

import java.util.UUID;

/**
 * {@code identity.security.alert} v2: v1 plus lockout and MFA kinds (P05). Identity produces only v2 from P05 on;
 * there were no v1 consumers (A-38).
 */
public record SecurityAlertV2(String kind, UUID refId, String occurredAt) {
  public static final String TYPE = "identity.security.alert";
  public static final int VERSION = 2;
  public static final String REFRESH_TOKEN_REUSE = "REFRESH_TOKEN_REUSE";
  public static final String ACCOUNT_LOCKED = "ACCOUNT_LOCKED";
  public static final String MFA_ENABLED = "MFA_ENABLED";
  public static final String MFA_DISABLED = "MFA_DISABLED";
  public static final String RECOVERY_CODES_REGENERATED = "RECOVERY_CODES_REGENERATED";
  public static final String RECOVERY_CODE_USED = "RECOVERY_CODE_USED";
}
