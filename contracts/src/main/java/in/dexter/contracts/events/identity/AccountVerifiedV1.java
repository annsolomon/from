package in.dexter.contracts.events.identity;

import java.util.UUID;

/** {@code identity.account.verified} v1: the email is verified and the account is active. */
public record AccountVerifiedV1(UUID accountId, String verifiedAt) {
  public static final String TYPE = "identity.account.verified";
  public static final int VERSION = 1;
}
