package in.dexter.testkit.demo.infra;

import in.dexter.cache.CacheInvalidator;
import in.dexter.cache.CacheScope;
import in.dexter.cache.InvalidationConsumer;
import in.dexter.events.EventEnvelope;
import in.dexter.testkit.demo.DemoEvents;
import in.dexter.testkit.demo.config.CacheScopes;
import java.util.List;
import java.util.Map;

/**
 * Path B (ADR-0004 §4): the demo's own events bump the {@code notes} scope again, so a bump lost between
 * commit and Redis (crash, Redis blip) is repaired when the event arrives. Run with an EventConsumer on
 * {@link DemoEvents#TOPIC}; no inbox (a duplicate bump costs one miss, see A-20).
 */
public final class NoteCacheInvalidation extends InvalidationConsumer {
  private static final Map<String, List<CacheScope>> SCOPES = Map.of(
      DemoEvents.NOTE_CREATED.type(), List.of(CacheScopes.NOTES),
      DemoEvents.NOTE_RENAMED.type(), List.of(CacheScopes.NOTES));

  public NoteCacheInvalidation(CacheInvalidator invalidator) {
    super(invalidator);
  }

  @Override
  protected List<CacheScope> scopesFor(EventEnvelope event) {
    return SCOPES.getOrDefault(event.type(), List.of());
  }
}
