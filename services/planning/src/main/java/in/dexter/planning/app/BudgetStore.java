package in.dexter.planning.app;

import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Budgets, their per-period spend and the thresholds already alerted (planning_app pool, RLS per account). */
public interface BudgetStore {

  /** @param categoryPath null means the budget is on total spend */
  record BudgetRow(UUID id, UUID accountId, String name, String period, String categoryPath, long limitPaise,
      boolean rollover, List<Integer> alertThresholds, LocalDate startsOn, LocalDate endsOn, String status,
      Instant createdAt, Instant updatedAt, int version) {}

  record SpendRow(UUID budgetId, LocalDate periodStart, long spentPaise, long projectedVersion, Instant updatedAt) {}

  void insert(BudgetRow b);

  Optional<BudgetRow> find(UUID id);

  Optional<BudgetRow> lock(UUID id);

  void update(BudgetRow b);

  Page<BudgetRow> list(PageRequest page);

  /** Every active budget of the tenant: few per account, so the consumer and the status page both read them all. */
  List<BudgetRow> active();

  Optional<SpendRow> spend(UUID budgetId, LocalDate periodStart);

  /** One budget's periods, newest first. */
  Page<SpendRow> history(PageRequest page, UUID budgetId);

  /**
   * Applies a delta to a period's spend and returns the new total. Floored at zero: an expense recorded before the
   * budget existed was never added, so its later deletion must not drive the row negative. The floor is also on the
   * inserted row, because Postgres checks the proposed INSERT before {@code ON CONFLICT} (the P07 lesson, A-47).
   */
  long addSpend(UUID accountId, UUID budgetId, LocalDate periodStart, long deltaPaise, long version);

  List<Integer> alertsSent(UUID budgetId, LocalDate periodStart);

  /** @return true when this call is the one that recorded the alert (at most once per period, enforced by the key) */
  boolean markAlertSent(UUID accountId, UUID budgetId, LocalDate periodStart, int threshold, long spentPaise);
}
