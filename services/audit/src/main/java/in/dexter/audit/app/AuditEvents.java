package in.dexter.audit.app;

import in.dexter.contracts.events.audit.PrivacyRequestedV1;
import in.dexter.events.EventType;
import java.util.List;

/** The events the audit service publishes (its outbox) and the aggregates its relay declares. */
public final class AuditEvents {
  public static final EventType<PrivacyRequestedV1> PRIVACY_REQUESTED = EventType.of("audit", "privacy", "requested",
      PrivacyRequestedV1.VERSION, PrivacyRequestedV1.class);
  public static final List<String> AGGREGATES = List.of("privacy");

  private AuditEvents() {}
}
