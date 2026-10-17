package in.dexter.contracts.events.identity;

import java.util.UUID;

/**
 * {@code identity.account.registered} v1 ({@code contracts/events/identity.account.registered.v1.json}).
 * Carries no PII (MASTER_PLAN 7.4): consumers that need the email ask identity.
 */
public record AccountRegisteredV1(UUID accountId, String tz, String registeredAt) {
  public static final String TYPE = "identity.account.registered";
  public static final int VERSION = 1;
}
