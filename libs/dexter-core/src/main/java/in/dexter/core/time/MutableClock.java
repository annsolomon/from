package in.dexter.core.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A settable {@link Clock} for tests. Production code always takes a {@link Clock} bean
 * ({@link Clocks#systemUtc()}) and never calls {@code Instant.now()} directly.
 */
public final class MutableClock extends Clock {
  private final AtomicReference<Instant> now;
  private final ZoneId zone;

  public MutableClock(Instant start) {
    this(new AtomicReference<>(start), ZoneOffset.UTC);
  }

  private MutableClock(AtomicReference<Instant> now, ZoneId zone) {
    this.now = now;
    this.zone = zone;
  }

  public void set(Instant instant) {
    now.set(instant);
  }

  public Instant advance(Duration d) {
    return now.updateAndGet(i -> i.plus(d));
  }

  @Override
  public ZoneId getZone() {
    return zone;
  }

  @Override
  public Clock withZone(ZoneId z) {
    return new MutableClock(now, z);
  }

  @Override
  public Instant instant() {
    return now.get();
  }
}
