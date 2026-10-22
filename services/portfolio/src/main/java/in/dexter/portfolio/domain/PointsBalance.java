package in.dexter.portfolio.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A card's reward points balance (MASTER_PLAN P11): earned lots are consumed oldest-expiry first by redemptions,
 * expiries and negative adjustments (positive adjustments add a lot without expiry). What is left of lots expiring
 * within the horizon is "expiring soon". Lots already past their expiry date but not yet written off by an
 * {@code expire} entry count as expired, not available. Pure.
 */
public final class PointsBalance {
  /** One ledger entry, in posting order. */
  public record Entry(String kind, long points, LocalDate expiresOn) {}

  public record Expiring(LocalDate on, long points) {}

  public record Result(long available, long lapsed, long expiringSoon, List<Expiring> soon) {}

  private PointsBalance() {}

  public static Result of(List<Entry> entries, LocalDate today, int horizonDays) {
    List<long[]> lots = new ArrayList<>(); // [epochDay or Long.MAX for no expiry, remaining]
    long debt = 0;
    for (Entry e : entries) {
      if (e.points() > 0) {
        lots.add(new long[] {e.expiresOn() == null ? Long.MAX_VALUE : e.expiresOn().toEpochDay(), e.points()});
      } else {
        debt += -e.points();
      }
    }
    lots.sort(Comparator.comparingLong(l -> l[0]));
    for (long[] lot : lots) {
      long take = Math.min(lot[1], debt);
      lot[1] -= take;
      debt -= take;
    }
    long available = 0;
    long lapsed = 0;
    long soonTotal = 0;
    List<Expiring> soon = new ArrayList<>();
    long todayDay = today.toEpochDay();
    long horizon = todayDay + horizonDays;
    for (long[] lot : lots) {
      if (lot[1] == 0) {
        continue;
      }
      if (lot[0] != Long.MAX_VALUE && lot[0] < todayDay) {
        lapsed += lot[1];
        continue;
      }
      available += lot[1];
      if (lot[0] <= horizon) {
        soonTotal += lot[1];
        LocalDate on = LocalDate.ofEpochDay(lot[0]);
        if (!soon.isEmpty() && soon.getLast().on().equals(on)) {
          soon.set(soon.size() - 1, new Expiring(on, soon.getLast().points() + lot[1]));
        } else {
          soon.add(new Expiring(on, lot[1]));
        }
      }
    }
    // more consumed than ever earned (adjustments): the balance is negative
    return new Result(available - debt, lapsed, soonTotal, List.copyOf(soon));
  }
}
