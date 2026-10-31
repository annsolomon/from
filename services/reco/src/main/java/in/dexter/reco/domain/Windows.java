package in.dexter.reco.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * The 3m / 6m / 12m windows of the spend profile (MASTER_PLAN P14), in whole local months.
 *
 * <p>A window of N months ends with the current month and reaches back N−1 more, so the 3m window on 15 March
 * covers January, February and March. The current month is partial, which drags the average down early in a
 * month; {@link #monthlyAverage} divides by the months that have actually contributed rather than by N, so a
 * profile built on the 2nd of a month is not a twelfth of the truth.
 */
public final class Windows {
  public static final List<String> ALL = List.of("3m", "6m", "12m");

  private Windows() {}

  public static int months(String window) {
    return switch (window) {
      case "3m" -> 3;
      case "6m" -> 6;
      case "12m" -> 12;
      default -> throw new IllegalArgumentException("unknown window " + window);
    };
  }

  /** The first month of the window containing {@code today} (the first day of that month). */
  public static LocalDate start(String window, LocalDate today) {
    return today.withDayOfMonth(1).minusMonths(months(window) - 1L);
  }

  /** The month {@code day} counts in: the first day of its month. */
  public static LocalDate month(LocalDate day) {
    return day.withDayOfMonth(1);
  }

  /** Whether {@code month} falls inside the window ending in {@code today}'s month. */
  public static boolean contains(String window, LocalDate today, LocalDate month) {
    LocalDate from = start(window, today);
    LocalDate to = today.withDayOfMonth(1);
    return !month.isBefore(from) && !month.isAfter(to);
  }

  /**
   * The monthly average of {@code totalPaise} spread over the months that contributed.
   *
   * @param monthsWithSpend how many distinct months inside the window have a row; 0 gives 0 rather than dividing
   *     by zero
   */
  public static long monthlyAverage(long totalPaise, int monthsWithSpend) {
    if (monthsWithSpend <= 0 || totalPaise <= 0) {
      return 0;
    }
    return totalPaise / monthsWithSpend;
  }
}
