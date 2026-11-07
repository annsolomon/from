package in.dexter.contracts.events.billing;

import java.util.Map;
import java.util.UUID;

/**
 * {@code billing.entitlements.changed} v1 (MASTER_PLAN P16): what an account may use changed - its tier and the
 * effective feature map (the plan's, with any unexpired override laid over it). Identity keeps the tier and puts it in
 * the {@code tier} claim of the next token it issues; {@code version} grows by one per change for the account, so a
 * late delivery of an older event is recognisable and ignored.
 */
public record EntitlementsChangedV1(UUID accountId, String tier, Map<String, Object> entitlements, long version,
    String changedAt) {
  public static final String TYPE = "billing.entitlements.changed";
  public static final int VERSION = 1;
  public static final String TOPIC = "dexter.billing.entitlements.v1";
}
