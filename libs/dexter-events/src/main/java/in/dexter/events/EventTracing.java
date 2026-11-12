package in.dexter.events;

import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The tracing seam of the event library (MASTER_PLAN P19 step 1): where the current W3C {@code traceparent} comes from
 * when an event is written, and how handling a consumed event continues the trace it carries. A service that traces
 * (dexter-observability) installs a Micrometer-Tracing implementation at startup; otherwise both are no-ops. Static,
 * because consumers and relays are built by hand in each service's configuration.
 */
public final class EventTracing {
  /** The tracing operations the library needs. */
  public interface Tracing {
    /** The current span as a W3C traceparent, or null outside a sampled span. */
    @Nullable String traceparent();

    /** Runs {@code work} inside a consumer span whose parent is {@code event.traceparent()} (a new trace if none). */
    void consume(String consumer, EventEnvelope event, ThrowingRunnable work) throws Exception;
  }

  /** A unit of work that may throw (the handler). */
  @FunctionalInterface
  public interface ThrowingRunnable {
    void run() throws Exception;
  }

  static final Tracing NONE = new Tracing() {
    @Override
    public @Nullable String traceparent() {
      return null;
    }

    @Override
    public void consume(String consumer, EventEnvelope event, ThrowingRunnable work) throws Exception {
      work.run();
    }
  };

  private static volatile Tracing current = NONE;

  private EventTracing() {}

  public static void install(Tracing tracing) {
    current = Objects.requireNonNull(tracing, "tracing");
  }

  public static void reset() {
    current = NONE;
  }

  public static Tracing current() {
    return current;
  }

  /** For {@link OutboxWriter}: the traceparent at the moment of writing. */
  public static Supplier<@Nullable String> traceparentSupplier() {
    return () -> current.traceparent();
  }
}
