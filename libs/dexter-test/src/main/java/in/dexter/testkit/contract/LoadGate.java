package in.dexter.testkit.contract;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Pauses one cache load after it has read the database and before it writes the cache: the window in which
 * ADR-0004 §3's reader/writer race happens. The service's test seam calls {@link #loaded()}.
 */
public final class LoadGate {
  private final CountDownLatch loaded = new CountDownLatch(1);
  private final CountDownLatch release = new CountDownLatch(1);

  /** Called by the loader after its DB read; blocks until {@link #release()} (at most 30 s). */
  public void loaded() {
    loaded.countDown();
    try {
      if (!release.await(30, TimeUnit.SECONDS)) {
        throw new IllegalStateException("LoadGate never released");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  public void awaitLoaded(Duration max) throws InterruptedException {
    if (!loaded.await(max.toMillis(), TimeUnit.MILLISECONDS)) {
      throw new AssertionError("the paused load never happened");
    }
  }

  public void release() {
    release.countDown();
  }
}
