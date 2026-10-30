package in.dexter.planning.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * What a budget period looks like right now, and where it is heading (MASTER_PLAN P13: budgets + spend projection).
 * Pure: paise in, paise out, and the caller supplies "today".
 */
public final class BudgetMath {

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private BudgetMath() {}

  /**
   * @param usedBps spend against the limit in basis points (10000 = exactly on the limit), so a UI needs no
   *     floating point to draw a bar
   * @param projectedSpendPaise the whole period's spend at the rate so far, which is the spend itself once the
   *     period is over
   * @param projectedOverspendPaise how far past the limit that rate lands, or zero
   * @param daysElapsed counts today, so day one of a period projects from one day of spending
   */
  public record Status(LocalDate periodStart, LocalDate periodEnd, long limitPaise, long spentPaise,
      long remainingPaise, int usedBps, boolean overLimit, long projectedSpendPaise, long projectedOverspendPaise,
      int daysElapsed, int daysInPeriod, List<Integer> thresholdsCrossed) {}

  /**
   * @param today may be past {@code periodEnd} (a finished period: elapsed is the whole period and the projection is
   *     the spend)
   */
  public static Status status(long limitPaise, long spentPaise, LocalDate periodStart, LocalDate periodEnd,
      LocalDate today, List<Integer> thresholds) {
    if (limitPaise <= 0) {
      throw new IllegalArgumentException("a budget limit is positive");
    }
    if (periodEnd.isBefore(periodStart)) {
      throw new IllegalArgumentException("periodEnd must not be before periodStart");
    }
    int daysInPeriod = (int) ChronoUnit.DAYS.between(periodStart, periodEnd) + 1;
    int elapsed = today.isBefore(periodStart) ? 1
        : (int) Math.min(daysInPeriod, ChronoUnit.DAYS.between(periodStart, today) + 1);
    long projected = projectedSpend(spentPaise, elapsed, daysInPeriod);
    return new Status(periodStart, periodEnd, limitPaise, spentPaise, limitPaise - spentPaise,
        usedBps(spentPaise, limitPaise), spentPaise > limitPaise, projected,
        Math.max(0, projected - limitPaise), elapsed, daysInPeriod, crossed(spentPaise, limitPaise, thresholds));
  }

  /** Spend against the limit in basis points, rounded HALF_UP to the basis point. */
  public static int usedBps(long spentPaise, long limitPaise) {
    return BigDecimal.valueOf(spentPaise).multiply(BigDecimal.valueOf(10_000))
        .divide(BigDecimal.valueOf(limitPaise), 0, RoundingMode.HALF_UP).intValueExact();
  }

  /** The whole period at the rate so far: {@code spent / daysElapsed × daysInPeriod}, HALF_UP to the paisa. */
  public static long projectedSpend(long spentPaise, int daysElapsed, int daysInPeriod) {
    if (daysElapsed < 1 || daysInPeriod < daysElapsed) {
      throw new IllegalArgumentException("1 <= daysElapsed <= daysInPeriod");
    }
    return BigDecimal.valueOf(spentPaise).multiply(BigDecimal.valueOf(daysInPeriod))
        .divide(BigDecimal.valueOf(daysElapsed), 0, RoundingMode.HALF_UP).longValueExact();
  }

  /**
   * The thresholds this spend has reached, in order. A threshold is a percentage of the limit, compared exactly:
   * {@code spent × 100 ≥ limit × threshold}, so 50 % of ₹1,000 is reached at exactly ₹500 and not a paisa earlier.
   */
  public static List<Integer> crossed(long spentPaise, long limitPaise, List<Integer> thresholds) {
    List<Integer> out = new ArrayList<>();
    if (thresholds == null) {
      return out;
    }
    BigDecimal spent = BigDecimal.valueOf(spentPaise).multiply(HUNDRED);
    for (int t : thresholds.stream().sorted().distinct().toList()) {
      if (spent.compareTo(BigDecimal.valueOf(limitPaise).multiply(BigDecimal.valueOf(t))) >= 0) {
        out.add(t);
      }
    }
    return List.copyOf(out);
  }

  /** The thresholds to alert on now: those reached and not already sent this period. */
  public static List<Integer> toAlert(long spentPaise, long limitPaise, List<Integer> thresholds,
      List<Integer> alreadySent) {
    List<Integer> sent = alreadySent == null ? List.of() : alreadySent;
    return crossed(spentPaise, limitPaise, thresholds).stream().filter(t -> !sent.contains(t)).toList();
  }
}
