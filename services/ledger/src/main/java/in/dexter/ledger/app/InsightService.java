package in.dexter.ledger.app;

import in.dexter.core.error.FieldError;
import in.dexter.core.error.ValidationException;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import in.dexter.ledger.app.DailyTotalsStore.DayTotal;
import in.dexter.ledger.app.LiabilitySource.Liability;
import in.dexter.ledger.domain.LedgerTime;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code GET /v1/calendar} and {@code GET /v1/summary} (MASTER_PLAN P08). Day and category-root views come from
 * {@code expense_daily_totals} (one indexed range read, no expense scan); merchant and payment-method views group
 * the live expenses of the range (bounded by {@code expenses_list}). All days are IST dates (A-45).
 */
public final class InsightService {
  public static final int MAX_DAYS = 366;
  public static final int MAX_GROUPS = 50;

  public record CalendarDay(LocalDate day, long spentPaise, int txnCount, Map<String, Long> byRoot,
      List<Liability> upcoming) {}

  public record Calendar(String month, long spentPaise, int txnCount, long upcomingPaise, List<CalendarDay> days) {}

  public record SummaryGroup(String key, String label, long totalPaise, int txnCount, int shareBps) {}

  public record Summary(String groupBy, LocalDate from, LocalDate to, long totalPaise, int txnCount,
      List<SummaryGroup> groups) {}

  private final TenantTx tx;
  private final DailyTotalsStore daily;
  private final ExpenseStore expenses;
  private final CategoryStore categories;
  private final LiabilitySource liabilities;

  public InsightService(TenantTx tx, DailyTotalsStore daily, ExpenseStore expenses, CategoryStore categories,
      LiabilitySource liabilities) {
    this.tx = tx;
    this.daily = daily;
    this.expenses = expenses;
    this.categories = categories;
    this.liabilities = liabilities;
  }

  /** Every day of the month (zeros included) with spend per category root and the liabilities due that day. */
  public Calendar calendar(Tenant tenant, String month) {
    YearMonth ym;
    try {
      ym = YearMonth.parse(month == null ? "" : month);
    } catch (DateTimeParseException e) {
      throw new ValidationException(List.of(new FieldError("month", "INVALID", "YYYY-MM")));
    }
    LocalDate from = ym.atDay(1);
    LocalDate to = ym.atEndOfMonth();
    return tx.read(tenant, () -> {
      Map<LocalDate, List<DayTotal>> byDay = new HashMap<>();
      for (DayTotal t : daily.range(tenant.accountId(), from, to)) {
        byDay.computeIfAbsent(t.day(), d -> new ArrayList<>()).add(t);
      }
      Map<LocalDate, List<Liability>> due = new HashMap<>();
      for (Liability l : liabilities.between(tenant.accountId(), from, to)) {
        due.computeIfAbsent(l.day(), d -> new ArrayList<>()).add(l);
      }
      List<CalendarDay> days = new ArrayList<>();
      long spent = 0;
      int n = 0;
      long upcoming = 0;
      for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
        Map<String, Long> roots = new TreeMap<>();
        long daySpent = 0;
        int dayN = 0;
        for (DayTotal t : byDay.getOrDefault(d, List.of())) {
          roots.merge(t.categoryRoot(), t.totalPaise(), Long::sum);
          daySpent += t.totalPaise();
          dayN += t.txnCount();
        }
        List<Liability> dayDue = due.getOrDefault(d, List.of());
        upcoming += dayDue.stream().mapToLong(Liability::amountPaise).sum();
        days.add(new CalendarDay(d, daySpent, dayN, roots, dayDue));
        spent += daySpent;
        n += dayN;
      }
      return new Calendar(ym.toString(), spent, n, upcoming, days);
    });
  }

  /** {@code groupBy}: category (root) | day | merchant (top {@value #MAX_GROUPS}) | paymentMethod. */
  public Summary summary(Tenant tenant, LocalDate from, LocalDate to, String groupBy) {
    List<FieldError> errors = new ArrayList<>();
    if (from == null || to == null || to.isBefore(from) || from.plusDays(MAX_DAYS - 1).isBefore(to)) {
      errors.add(new FieldError("to", "INVALID_RANGE", "from and to are required, to >= from, at most " + MAX_DAYS
          + " days"));
    }
    String by = groupBy == null ? "category" : groupBy;
    if (!List.of("category", "day", "merchant", "paymentMethod").contains(by)) {
      errors.add(new FieldError("groupBy", "INVALID", "category, day, merchant or paymentMethod"));
    }
    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
    return tx.read(tenant, () -> {
      List<ExpenseStore.Group> groups = switch (by) {
        case "category", "day" -> fromDailyTotals(tenant, from, to, by);
        default -> expenses.groups(by, from.atStartOfDay(LedgerTime.ZONE).toInstant(),
            to.plusDays(1).atStartOfDay(LedgerTime.ZONE).toInstant(), MAX_GROUPS);
      };
      long total;
      int n;
      if (by.equals("merchant")) {
        // the merchant view is capped: the totals come from the uncapped daily totals
        List<DayTotal> all = daily.range(tenant.accountId(), from, to);
        total = all.stream().mapToLong(DayTotal::totalPaise).sum();
        n = all.stream().mapToInt(DayTotal::txnCount).sum();
      } else {
        total = groups.stream().mapToLong(ExpenseStore.Group::totalPaise).sum();
        n = groups.stream().mapToInt(ExpenseStore.Group::txnCount).sum();
      }
      long t = total;
      List<SummaryGroup> out = groups.stream()
          .map(g -> new SummaryGroup(g.key(), g.label(), g.totalPaise(), g.txnCount(),
              t == 0 ? 0 : (int) Math.round(g.totalPaise() * 10_000.0 / t)))
          .toList();
      return new Summary(by, from, to, total, n, out);
    });
  }

  private List<ExpenseStore.Group> fromDailyTotals(Tenant tenant, LocalDate from, LocalDate to, String by) {
    Map<String, long[]> acc = new LinkedHashMap<>();
    for (DayTotal t : daily.range(tenant.accountId(), from, to)) {
      String k = by.equals("day") ? t.day().toString() : t.categoryRoot();
      long[] v = acc.computeIfAbsent(k, x -> new long[2]);
      v[0] += t.totalPaise();
      v[1] += t.txnCount();
    }
    Map<String, String> names = new HashMap<>();
    if (by.equals("category")) {
      categories.listVisible().forEach(c -> names.putIfAbsent(c.path(), c.name()));
    }
    List<ExpenseStore.Group> out = new ArrayList<>();
    acc.forEach((k, v) -> out.add(new ExpenseStore.Group(k, names.getOrDefault(k, k), v[0], (int) v[1])));
    if (by.equals("category")) {
      out.sort(Comparator.comparingLong(ExpenseStore.Group::totalPaise).reversed()
          .thenComparing(ExpenseStore.Group::key));
    }
    return out;
  }
}
