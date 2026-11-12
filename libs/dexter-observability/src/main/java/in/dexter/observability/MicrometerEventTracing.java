package in.dexter.observability;

import in.dexter.events.EventEnvelope;
import in.dexter.events.EventTracing;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.jspecify.annotations.Nullable;

/**
 * The event library's tracing seam over Micrometer Tracing (OpenTelemetry bridge): an event written inside a request
 * carries the request's span as a W3C traceparent; the consumer that handles it opens a CONSUMER span whose parent
 * is that traceparent, so one trace runs gateway → service → outbox → Kafka → consumer → whatever it writes.
 */
public final class MicrometerEventTracing implements EventTracing.Tracing {
  private final Tracer tracer;
  private final Propagator propagator;

  public MicrometerEventTracing(Tracer tracer, Propagator propagator) {
    this.tracer = tracer;
    this.propagator = propagator;
  }

  @Override
  public @Nullable String traceparent() {
    Span span = tracer.currentSpan();
    if (span == null) {
      return null;
    }
    return traceparent(span.context());
  }

  /** {@code 00-<trace id>-<span id>-<flags>} (W3C Trace Context). */
  static @Nullable String traceparent(TraceContext c) {
    String traceId = c.traceId();
    String spanId = c.spanId();
    if (traceId == null || spanId == null || traceId.isBlank() || spanId.isBlank()) {
      return null;
    }
    return "00-" + pad(traceId, 32) + "-" + pad(spanId, 16) + "-" + (Boolean.TRUE.equals(c.sampled()) ? "01" : "00");
  }

  static String pad(String hex, int len) {
    return hex.length() >= len ? hex : "0".repeat(len - hex.length()) + hex;
  }

  @Override
  public void consume(String consumer, EventEnvelope event, EventTracing.ThrowingRunnable work) throws Exception {
    String parent = event.traceparent();
    Span.Builder builder = parent == null || parent.isBlank() ? tracer.spanBuilder()
        : propagator.extract(event, (carrier, key) -> "traceparent".equalsIgnoreCase(key) ? carrier.traceparent()
            : null);
    Span span = builder.name("consume " + event.type()).kind(Span.Kind.CONSUMER)
        .tag("messaging.system", "kafka")
        .tag("messaging.consumer.group.name", consumer)
        .tag("dexter.event.id", event.id().toString())
        .tag("dexter.event.type", event.type())
        .start();
    try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
      work.run();
    } catch (Exception | Error e) {
      span.error(e);
      throw e;
    } finally {
      span.end();
    }
  }
}
