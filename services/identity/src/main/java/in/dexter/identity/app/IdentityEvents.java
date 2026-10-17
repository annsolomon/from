package in.dexter.identity.app;

import in.dexter.contracts.events.identity.AccountRegisteredV1;
import in.dexter.contracts.events.identity.AccountVerifiedV1;
import in.dexter.contracts.events.identity.SecurityAlertV1;
import in.dexter.contracts.events.identity.SessionRevokedV1;
import in.dexter.events.EventType;
import java.util.List;

/** Identity's published events (MASTER_PLAN P04; schemas in contracts/events/identity.*.v1.json). */
public final class IdentityEvents {
  /** {@code dexter.identity.account.v1}, key = account id. */
  public static final EventType<AccountRegisteredV1> ACCOUNT_REGISTERED =
      EventType.of("identity", "account", "registered", AccountRegisteredV1.VERSION, AccountRegisteredV1.class);
  public static final EventType<AccountVerifiedV1> ACCOUNT_VERIFIED =
      EventType.of("identity", "account", "verified", AccountVerifiedV1.VERSION, AccountVerifiedV1.class);
  /** {@code dexter.identity.session.v1}, key = account id (envelope accountId), aggregate = session. */
  public static final EventType<SessionRevokedV1> SESSION_REVOKED =
      EventType.of("identity", "session", "revoked", SessionRevokedV1.VERSION, SessionRevokedV1.class);
  /** {@code dexter.identity.security.v1}, key = account id. */
  public static final EventType<SecurityAlertV1> SECURITY_ALERT =
      EventType.of("identity", "security", "alert", SecurityAlertV1.VERSION, SecurityAlertV1.class);

  /** Topics identity produces (created at startup by the relay, MASTER_PLAN 6.2). */
  public static final List<String> AGGREGATES = List.of("account", "session", "security");

  private IdentityEvents() {}
}
