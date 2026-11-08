package in.dexter.commerce.app;

import in.dexter.contracts.events.commerce.ApplicationStatusChangedV1;
import in.dexter.events.EventType;
import java.util.List;

/** Commerce's event types (contracts/events/commerce.*.v1.json). */
public final class CommerceEvents {
  /** A card application changed status; P18 notifies the applicant. */
  public static final EventType<ApplicationStatusChangedV1> APPLICATION_CHANGED = EventType.of("commerce",
      "application", "status-changed", ApplicationStatusChangedV1.VERSION, ApplicationStatusChangedV1.class);

  /** The aggregates commerce publishes (topics dexter.commerce.&lt;aggregate&gt;.v1, created by the relay). */
  public static final List<String> AGGREGATES = List.of("application");

  private CommerceEvents() {}
}
