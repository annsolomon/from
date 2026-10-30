package in.dexter.planning.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Which period of a budget a day falls in (MASTER_PLAN P13). Periods repeat from the budget's {@code starts_on}:
 * {@code weekly} every 7 days, {@code monthly} on the same day of each month (clamped in a short month, so a budget
 * anchored on the 31st runs 31 Jan → 28 Feb → 31 Mar), and {@code custom} is the single window
 * {@code starts_on … ends_on}. A budget anchored on the 1st therefore has calendar months.
 *
 * <p>Days are the account's local days. The ledger already stamps every expense with its Asia/Kolkata day
 * ({@code ExpenseStateV1.day}), so an expense at 23:30 IST on the 31st arrives as the 31st and lands in that
 * month's period — the boundary is decided once, in the ledger, not again here.
 */
public final class BudgetPeriods {

  /** How a budget repeats. */
  public enum Period {
    WEEKLY, MONTHLY, CUSTOM;

    public static Period of(String stored) {
      return switch (stored) {
        case "weekly" -> WEEKLY;
        case "monthly" -> MONTHLY;
        case "custom" -> CUSTOM;
        default -> throw new IllegalArgumentException("unknown budget period: " + stored);
      };
    }

    public String stored() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  private static final int DAYS_A_WEEK = 7;

  private BudgetPeriods() {}

  /** The period containing {@code day}, or empty when the day is outside the budget's window. */
  public static Optional<LocalDate> periodStart(Period period, LocalDate startsOn, LocalDate endsOn, LocalDate day) {
    if (day.isBefore(startsOn) || endsOn != null && day.isAfter(endsOn)) {
      return Optional.empty();
    }
    return Optional.of(switch (period) {
      case CUSTOM -> startsOn;
      case WEEKLY -> startsOn.plusDays(ChronoUnit.DAYS.between(startsOn, day) / DAYS_A_WEEK * (long) DAYS_A_WEEK);
      case MONTHLY -> monthlyStart(startsOn, day);
    });
  }

  /**
   * The last day of the period that began on {@code periodStart}: the day before the next period begins, and never
   * past the budget's {@code ends_on}.
   */
  public static LocalDate periodEnd(Period period, LocalDate startsOn, LocalDate endsOn, LocalDate periodStart) {
    LocalDate end = switch (period) {
      case CUSTOM -> endsOn == null ? periodStart : endsOn;
      case WEEKLY -> periodStart.plusDays(DAYS_A_WEEK - 1L);
      case MONTHLY -> nextMonthlyStart(startsOn, periodStart).minusDays(1);
    };
    return endsOn != null && end.isAfter(endsOn) ? endsOn : end;
  }

  /** The period before this one, or empty when it would start before the budget did. */
  public static Optional<LocalDate> previousPeriodStart(Period period, LocalDate startsOn, LocalDate periodStart) {
    if (!periodStart.isAfter(startsOn)) {
      return Optional.empty();
    }
    return switch (period) {
      case CUSTOM -> Optional.empty();
      case WEEKLY -> Optional.of(periodStart.minusDays(DAYS_A_WEEK));
      case MONTHLY -> {
        long months = monthsBetweenAnchored(startsOn, periodStart);
        yield months <= 0 ? Optional.empty() : Optional.of(anchored(startsOn, months - 1));
      }
    };
  }

  private static LocalDate monthlyStart(LocalDate startsOn, LocalDate day) {
    long months = (day.getYear() - startsOn.getYear()) * 12L + (day.getMonthValue() - startsOn.getMonthValue());
    LocalDate candidate = anchored(startsOn, months);
    // the anchor day may fall later in the month than `day` does, and clamping can move it either way
    while (candidate.isAfter(day) && months > 0) {
      months--;
      candidate = anchored(startsOn, months);
    }
    while (months >= 0) {
      LocalDate next = anchored(startsOn, months + 1);
      if (next.isAfter(day)) {
        break;
      }
      months++;
      candidate = next;
    }
    return candidate;
  }

  private static LocalDate nextMonthlyStart(LocalDate startsOn, LocalDate periodStart) {
    return anchored(startsOn, monthsBetweenAnchored(startsOn, periodStart) + 1);
  }

  /** How many monthly periods after {@code startsOn} the period beginning on {@code periodStart} is. */
  private static long monthsBetweenAnchored(LocalDate startsOn, LocalDate periodStart) {
    long months = (periodStart.getYear() - startsOn.getYear()) * 12L
        + (periodStart.getMonthValue() - startsOn.getMonthValue());
    return Math.max(0, months);
  }

  /** {@code startsOn} plus n months, clamped to the end of a short month exactly as {@code plusMonths} clamps. */
  private static LocalDate anchored(LocalDate startsOn, long months) {
    return startsOn.plusMonths(months);
  }
}
