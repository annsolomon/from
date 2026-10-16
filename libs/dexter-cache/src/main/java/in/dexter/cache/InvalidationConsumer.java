package in.dexter.cache;

import in.dexter.events.EventEnvelope;
import in.dexter.events.consumer.EventConsumer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Path B (ADR-0004 §4): maps event types to the scopes they invalidate and bumps them. Run it with an
 * {@link EventConsumer} on every topic the service caches data from, including its own.
 *
 * <p>Deliberately <b>not</b> deduplicated through the inbox (A-20): a duplicate bump only costs one extra miss,
 * whereas recording the event in the inbox and then failing to reach Redis would lose the bump. A Redis failure
 * throws, so the event goes through the retry topics (1 min, 10 min) and finally the DLQ; the TTL is the bound.
 * Projections whose own cached reads depend on the projected rows bump from their transaction instead
 * ({@link CacheInvalidator#afterCommit}), so no reader sees the new generation before the new rows.
 */
public abstract class InvalidationConsumer implements EventConsumer.Handler {
  private static final Logger log = LoggerFactory.getLogger(InvalidationConsumer.class);
  private final CacheInvalidator invalidator;

  protected InvalidationConsumer(CacheInvalidator invalidator) {
    this.invalidator = Objects.requireNonNull(invalidator, "invalidator");
  }

  /** The scopes {@code event} invalidates; empty for types this service does not cache. */
  protected abstract List<CacheScope> scopesFor(EventEnvelope event);

  /** A consumer from a fixed {@code type → scopes} map. */
  public static InvalidationConsumer of(CacheInvalidator invalidator, Map<String, List<CacheScope>> byType) {
    Map<String, List<CacheScope>> copy = Map.copyOf(byType);
    return new InvalidationConsumer(invalidator) {
      @Override
      protected List<CacheScope> scopesFor(EventEnvelope event) {
        return copy.getOrDefault(event.type(), List.of());
      }
    };
  }

  @Override
  public final void handle(EventEnvelope event) {
    List<CacheScope> scopes = scopesFor(event);
    if (scopes.isEmpty()) {
      return;
    }
    List<CacheScope> tenant = new ArrayList<>();
    List<CacheScope> global = new ArrayList<>();
    for (CacheScope s : scopes) {
      (s.isTenant() ? tenant : global).add(s);
    }
    if (!tenant.isEmpty()) {
      if (event.accountId() == null) {
        log.warn("event {} ({}) has no accountId; per-account scopes {} not bumped", event.id(), event.type(),
            tenant.stream().map(CacheScope::name).toList());
      } else {
        invalidator.bump(event.accountId(), tenant);
      }
    }
    if (!global.isEmpty()) {
      invalidator.bumpGlobal(global);
    }
  }
}
