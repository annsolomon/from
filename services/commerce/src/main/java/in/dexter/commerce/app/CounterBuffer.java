package in.dexter.commerce.app;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The hot side of the counters (MASTER_PLAN 8.3): increments land in Redis and the flusher drains them into the
 * counter tables. Every drain of a key is atomic (read and delete in one script), so concurrent flushers — on one
 * instance or several — split the deltas without double counting; a failed write puts its deltas back.
 */
public interface CounterBuffer {
  enum Metric {
    DEAL_CLICKS("dc"), DEAL_UNIQUES("du"), AD_IMPRESSIONS("ai"), AD_CLICKS("ac");

    private final String code;

    Metric(String code) {
      this.code = code;
    }

    public String code() {
      return code;
    }

    public static Metric ofCode(String code) {
      for (Metric m : values()) {
        if (m.code.equals(code)) {
          return m;
        }
      }
      throw new IllegalArgumentException("unknown metric " + code);
    }
  }

  record Delta(Metric metric, LocalDate day, UUID id, long count) {}

  /** One click; also one unique when this visitor had not clicked this deal on this IST day. */
  void dealClick(UUID dealId, LocalDate day, byte[] visitorHash);

  void adClick(UUID creativeId, LocalDate day);

  /**
   * Counts one impression of the campaign for this visitor if they are under its frequency cap for the window, and
   * returns whether it did — atomically, so concurrent serves never exceed the cap.
   */
  boolean impression(UUID campaignId, UUID creativeId, LocalDate day, byte[] visitorHash, int cap, Duration window);

  /** Takes (and removes) up to {@code maxKeys} pending deltas. */
  List<Delta> drain(int maxKeys);

  /** Adds deltas back (after a failed write). */
  void restore(List<Delta> deltas);
}
