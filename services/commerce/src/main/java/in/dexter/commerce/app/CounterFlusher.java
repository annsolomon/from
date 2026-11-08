package in.dexter.commerce.app;

import in.dexter.commerce.app.CounterBuffer.Delta;
import in.dexter.commerce.app.CounterBuffer.Metric;
import in.dexter.db.tenant.TenantTx;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drains the Redis counters into deal_click_counters / ad_counters (MASTER_PLAN 8.3; every 10 s by default). One
 * batch is one TRACKER transaction of additive upserts; when it fails the batch's deltas go back into Redis, so a
 * flush either lands whole or is retried whole by the next one. Concurrent flushers each drain disjoint deltas.
 */
public final class CounterFlusher {
  public static final int BATCH = 500;

  private final TenantTx tx;
  private final TrackingStore tracking;
  private final CounterBuffer buffer;

  public CounterFlusher(TenantTx tx, TrackingStore tracking, CounterBuffer buffer) {
    this.tx = tx;
    this.tracking = tracking;
    this.buffer = buffer;
  }

  /** Flushes everything pending; returns the number of deltas written. */
  public int flush() {
    int total = 0;
    while (true) {
      List<Delta> batch = buffer.drain(BATCH);
      if (batch.isEmpty()) {
        return total;
      }
      write(batch);
      total += batch.size();
      if (batch.size() < BATCH) {
        return total;
      }
    }
  }

  private void write(List<Delta> batch) {
    record Key(boolean deal, UUID id, LocalDate day) {}
    Map<Key, long[]> rows = new LinkedHashMap<>();
    for (Delta d : batch) {
      boolean deal = d.metric() == Metric.DEAL_CLICKS || d.metric() == Metric.DEAL_UNIQUES;
      long[] r = rows.computeIfAbsent(new Key(deal, d.id(), d.day()), k -> new long[2]);
      int slot = d.metric() == Metric.DEAL_UNIQUES || d.metric() == Metric.AD_CLICKS ? 1 : 0;
      r[slot] += d.count();
    }
    try {
      tx.writeVoid(CommerceTenants.TRACKER, () -> rows.forEach((k, v) -> {
        if (k.deal()) {
          tracking.addDealCounters(k.id(), k.day(), v[0], v[1], 0, 0);
        } else {
          tracking.addAdCounters(k.id(), k.day(), v[0], v[1]);
        }
      }));
    } catch (RuntimeException e) {
      buffer.restore(batch);
      throw e;
    }
  }
}
