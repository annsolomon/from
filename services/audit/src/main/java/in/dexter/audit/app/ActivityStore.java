package in.dexter.audit.app;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Gateway activity: raw events (90 days) and the hourly aggregate the dashboard reads. */
public interface ActivityStore {
  record Event(UUID id, Instant occurredAt, @Nullable UUID accountId, String requestId, String service,
      String routePattern, String routeClass, String method, int status, long durationMs) {}

  record Hourly(Instant hour, String service, String routeClass, long requests, long clientErrors, long serverErrors,
      long totalMs, int maxMs) {}

  /** Inserts the event and adds it to its hour; false (and nothing added) when this event id was recorded. */
  boolean record(Event e);

  List<Hourly> hourly(Instant from, Instant to);
}
