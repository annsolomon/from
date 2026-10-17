package in.dexter.ledger.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The changes one expense write makes to {@code expense_daily_totals} (MASTER_PLAN P07): the old bucket loses the
 * old amount and one transaction, the new bucket gains the new ones. Create = no old, delete = no new, restore = no
 * old; an update that moves the day or the category root touches two buckets, one that keeps both touches one
 * (with the amount difference and no count change), and a no-op touches none.
 */
public final class DailyDelta {
  /** The part of an expense that daily totals depend on. Null = the expense does not count (absent or deleted). */
  public record Counted(LocalDate day, String categoryRoot, long amountPaise) {}

  public record Change(LocalDate day, String categoryRoot, long amountPaise, int count) {}

  private DailyDelta() {}

  public static List<Change> between(Counted before, Counted after) {
    record Key(LocalDate day, String root) {}
    Map<Key, long[]> m = new LinkedHashMap<>();
    if (before != null) {
      long[] v = m.computeIfAbsent(new Key(before.day(), before.categoryRoot()), k -> new long[2]);
      v[0] -= before.amountPaise();
      v[1] -= 1;
    }
    if (after != null) {
      long[] v = m.computeIfAbsent(new Key(after.day(), after.categoryRoot()), k -> new long[2]);
      v[0] += after.amountPaise();
      v[1] += 1;
    }
    List<Change> out = new ArrayList<>();
    m.forEach((k, v) -> {
      if (v[0] != 0 || v[1] != 0) {
        out.add(new Change(k.day(), k.root(), v[0], (int) v[1]));
      }
    });
    return out;
  }
}
