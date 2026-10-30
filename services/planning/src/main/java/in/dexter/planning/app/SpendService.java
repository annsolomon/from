package in.dexter.planning.app;

import in.dexter.contracts.events.ledger.ExpenseChangedV1;
import in.dexter.contracts.events.ledger.ExpenseStateV1;
import in.dexter.contracts.events.planning.BudgetThresholdCrossedV1;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.EventEnvelope;
import in.dexter.events.InboxGuard;
import in.dexter.events.OutboxWriter;
import in.dexter.planning.app.BudgetStore.BudgetRow;
import in.dexter.planning.domain.BudgetMath;
import in.dexter.planning.domain.Categories;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * Keeps every budget period's spend up to date from {@code ledger.expense.*} (MASTER_PLAN P13), and raises
 * {@code planning.budget.threshold_crossed} the first time a period reaches one of its thresholds.
 *
 * <p>Each event is applied as a <em>delta per matching budget</em>: the {@code before} state is subtracted from the
 * budgets and period it counted under and the {@code after} state is added to the budgets and period it counts under
 * now, so an edit that moves an expense between categories, days or amounts settles both sides. A create has no
 * {@code before}, a delete no {@code after}.
 *
 * <p>Exactly-once is the inbox row, written in the same transaction as the deltas. A delta is not idempotent under
 * replay, so the inbox — keyed on the event id — is what guards it; the row's {@code projected_version} records the
 * expense version last applied. A per-row version comparison would be wrong here and is deliberately not used:
 * one period row aggregates many expenses, and expense versions are per expense, so a low version from one expense
 * would wrongly skip an event after a high version from another (ADR-0019).
 *
 * <p>Only {@code active} budgets track spend: a paused budget is not tracking, by design.
 */
public final class SpendService {
  public static final String CONSUMER = "budget-spend";

  /** What one event did (tests and logs). */
  public enum Outcome { APPLIED, DUPLICATE, IGNORED }

  /** One period of one budget. */
  private record Slot(UUID budgetId, LocalDate periodStart) {}

  private final TenantTx tx;
  private final InboxGuard inbox;
  private final BudgetStore budgets;
  private final OutboxWriter outbox;
  private final ObjectMapper mapper;
  private final Clock clock;

  public SpendService(TenantTx tx, InboxGuard inbox, BudgetStore budgets, OutboxWriter outbox, ObjectMapper mapper,
      Clock clock) {
    this.tx = tx;
    this.inbox = inbox;
    this.budgets = budgets;
    this.outbox = outbox;
    this.mapper = mapper;
    this.clock = clock;
  }

  public Outcome onExpenseEvent(EventEnvelope e) {
    if (!e.type().startsWith("ledger.expense.") || e.accountId() == null) {
      return Outcome.IGNORED;
    }
    ExpenseChangedV1 c = mapper.treeToValue(e.data(), ExpenseChangedV1.class);
    if (c.before() == null && c.after() == null) {
      return Outcome.IGNORED;
    }
    UUID account = e.accountId();
    return tx.write(Tenant.user(account), () -> {
      if (!inbox.firstDelivery(CONSUMER, e.id())) {
        return Outcome.DUPLICATE;
      }
      List<BudgetRow> active = budgets.active();
      if (active.isEmpty()) {
        return Outcome.APPLIED;
      }
      Map<Slot, Long> deltas = new LinkedHashMap<>();
      accumulate(deltas, active, c.before(), -1);
      accumulate(deltas, active, c.after(), 1);

      Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
      for (Map.Entry<Slot, Long> d : deltas.entrySet()) {
        if (d.getValue() == 0) {
          continue;
        }
        Slot slot = d.getKey();
        long spent = budgets.addSpend(account, slot.budgetId(), slot.periodStart(), d.getValue(), c.version());
        alert(account, byId(active, slot.budgetId()), slot.periodStart(), spent, now);
      }
      return Outcome.APPLIED;
    });
  }

  /** Adds {@code sign × amount} to every active budget this expense counts under, in its own period. */
  private static void accumulate(Map<Slot, Long> deltas, List<BudgetRow> active, ExpenseStateV1 state, int sign) {
    if (state == null) {
      return;
    }
    LocalDate day = LocalDate.parse(state.day());
    for (BudgetRow b : active) {
      if (!Categories.matches(b.categoryPath(), state.categoryPath())) {
        continue;
      }
      BudgetService.periodStart(b, day).ifPresent(start ->
          deltas.merge(new Slot(b.id(), start), sign * state.amountPaise(), Long::sum));
    }
  }

  /** Each threshold this period has reached and not yet alerted, once, with its event in the same transaction. */
  private void alert(UUID account, BudgetRow b, LocalDate periodStart, long spent, Instant now) {
    if (b == null) {
      return;
    }
    List<Integer> sent = budgets.alertsSent(b.id(), periodStart);
    for (int threshold : BudgetMath.toAlert(spent, b.limitPaise(), b.alertThresholds(), sent)) {
      if (!budgets.markAlertSent(account, b.id(), periodStart, threshold, spent)) {
        continue;
      }
      outbox.append(PlanningEvents.BUDGET_THRESHOLD_CROSSED, account, b.id(), b.version(),
          new BudgetThresholdCrossedV1(b.id(), b.name(), b.period(), b.categoryPath(), periodStart.toString(),
              threshold, b.limitPaise(), spent, BudgetMath.usedBps(spent, b.limitPaise()), now.toString()));
    }
  }

  private static BudgetRow byId(List<BudgetRow> rows, UUID id) {
    for (BudgetRow r : rows) {
      if (r.id().equals(id)) {
        return r;
      }
    }
    return null;
  }

  /** The budgets an expense would count under, for the trail page and the tests. */
  public List<UUID> matching(List<BudgetRow> active, String categoryPath, LocalDate day) {
    List<UUID> out = new ArrayList<>();
    for (BudgetRow b : active) {
      if (Categories.matches(b.categoryPath(), categoryPath) && BudgetService.periodStart(b, day).isPresent()) {
        out.add(b.id());
      }
    }
    return List.copyOf(out);
  }
}
