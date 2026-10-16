package in.dexter.cache;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.concurrent.atomic.LongAdder;

/**
 * ADR-0004 §9 metrics: {@code cache_requests_total{result=hit|miss|bypass|stale_refresh}},
 * {@code cache_invalidation_failures_total} and {@code cache_loads_total} (DB loads), tagged with the service.
 * Counted in-process so tests can read them without a registry; {@link #bindTo} exposes them to Micrometer.
 */
public final class CacheMetrics implements MeterBinder {
  final LongAdder hit = new LongAdder();
  final LongAdder miss = new LongAdder();
  final LongAdder bypass = new LongAdder();
  final LongAdder staleRefresh = new LongAdder();
  final LongAdder loads = new LongAdder();
  final LongAdder invalidationFailures = new LongAdder();
  private final String service;

  public CacheMetrics(String service) {
    this.service = service;
  }

  public record Snapshot(long hit, long miss, long bypass, long staleRefresh, long loads, long invalidationFailures) {}

  public Snapshot snapshot() {
    return new Snapshot(hit.sum(), miss.sum(), bypass.sum(), staleRefresh.sum(), loads.sum(),
        invalidationFailures.sum());
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    request(registry, "hit", hit);
    request(registry, "miss", miss);
    request(registry, "bypass", bypass);
    request(registry, "stale_refresh", staleRefresh);
    FunctionCounter.builder("cache.loads", loads, LongAdder::doubleValue)
        .description("Cache-aside loads from the source of truth").tag("service", service).register(registry);
    FunctionCounter.builder("cache.invalidation.failures", invalidationFailures, LongAdder::doubleValue)
        .description("Generation bumps that failed (TTL bounds the staleness)").tag("service", service)
        .register(registry);
  }

  private void request(MeterRegistry registry, String result, LongAdder adder) {
    FunctionCounter.builder("cache.requests", adder, LongAdder::doubleValue)
        .description("Cache reads by result").tag("service", service).tag("result", result).register(registry);
  }
}
