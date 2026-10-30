package in.dexter.planning;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.planning.app.SpendService;
import in.dexter.planning.support.Events;
import in.dexter.planning.support.PlanningIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The budget-spend projection (MASTER_PLAN P13): ledger expense events become a delta per matching budget and
 * period, and each alert threshold fires once per period.
 *
 * <p>Includes the plan's named boundary case. The ledger stamps an expense with its Asia/Kolkata day, so an expense
 * at 23:30 IST on the 31st arrives as day "the 31st" — and must land in that month, not the next.
 */
class SpendProjectionIT extends PlanningIT {

  @Autowired
  SpendService spend;

  @Test
  void anExpenseCountsOnlyTowardsTheBudgetsItMatches() {
    UUID a = UUID.randomUUID();
    UUID dining = newBudget(a, budget("Dining", "monthly", "food.dining", 1_000_000L, "2026-01-01"));
    UUID everything = newBudget(a, budget("Everything", "monthly", null, 10_000_000L, "2026-01-01"));
    UUID travel = newBudget(a, budget("Travel", "monthly", "travel", 1_000_000L, "2026-01-01"));

    assertThat(spend.onExpenseEvent(Events.created(a, UUID.randomUUID(),
        Events.state(250_000L, "2026-01-15", "food.dining.fastfood"))))
        .isEqualTo(SpendService.Outcome.APPLIED);

    assertThat(spend(dining, "2026-01-01")).as("a child category counts").isEqualTo(250_000L);
    assertThat(spend(everything, "2026-01-01")).as("no category means all spend").isEqualTo(250_000L);
    assertThat(spend(travel, "2026-01-01")).as("another branch does not").isZero();
  }

  @Test
  void anExpenseLateOnTheLastDayOfTheMonthLandsInThatMonth() {
    UUID a = UUID.randomUUID();
    UUID monthly = newBudget(a, budget("Everything", "monthly", null, 10_000_000L, "2026-01-01"));

    // 23:30 IST on 31 January arrives from the ledger as day 2026-01-31
    spend.onExpenseEvent(Events.created(a, UUID.randomUUID(), Events.state(100_000L, "2026-01-31", "general")));
    // and the next day is February
    spend.onExpenseEvent(Events.created(a, UUID.randomUUID(), Events.state(700_000L, "2026-02-01", "general")));

    assertThat(spend(monthly, "2026-01-01")).isEqualTo(100_000L);
    assertThat(spend(monthly, "2026-02-01")).isEqualTo(700_000L);
  }

  @Test
  void anEditSettlesBothSidesOfTheMove() {
    UUID a = UUID.randomUUID();
    UUID dining = newBudget(a, budget("Dining", "monthly", "food.dining", 5_000_000L, "2026-01-01"));
    UUID travel = newBudget(a, budget("Travel", "monthly", "travel", 5_000_000L, "2026-01-01"));
    UUID expense = UUID.randomUUID();

    spend.onExpenseEvent(Events.created(a, expense, Events.state(300_000L, "2026-01-10", "food.dining")));
    assertThat(spend(dining, "2026-01-01")).isEqualTo(300_000L);

    // the amount changes
    spend.onExpenseEvent(Events.updated(a, expense, 1, Events.state(300_000L, "2026-01-10", "food.dining"),
        Events.state(500_000L, "2026-01-10", "food.dining")));
    assertThat(spend(dining, "2026-01-01")).isEqualTo(500_000L);

    // the category changes: it leaves one budget and joins the other
    spend.onExpenseEvent(Events.updated(a, expense, 2, Events.state(500_000L, "2026-01-10", "food.dining"),
        Events.state(500_000L, "2026-01-10", "travel")));
    assertThat(spend(dining, "2026-01-01")).isZero();
    assertThat(spend(travel, "2026-01-01")).isEqualTo(500_000L);

    // the day changes: it leaves one period and joins the next
    spend.onExpenseEvent(Events.updated(a, expense, 3, Events.state(500_000L, "2026-01-10", "travel"),
        Events.state(500_000L, "2026-02-10", "travel")));
    assertThat(spend(travel, "2026-01-01")).isZero();
    assertThat(spend(travel, "2026-02-01")).isEqualTo(500_000L);

    // deleting takes it back out
    spend.onExpenseEvent(Events.deleted(a, expense, 4, Events.state(500_000L, "2026-02-10", "travel")));
    assertThat(spend(travel, "2026-02-01")).isZero();

    // and restoring puts it back
    spend.onExpenseEvent(Events.restored(a, expense, 5, Events.state(500_000L, "2026-02-10", "travel")));
    assertThat(spend(travel, "2026-02-01")).isEqualTo(500_000L);
  }

  @Test
  void aDeleteOfSomethingNeverCountedIsFlooredAtZero() {
    UUID a = UUID.randomUUID();
    // the budget is created now, so an expense from before it existed was never added to any period
    UUID b = newBudget(a, budget("Everything", "monthly", null, 1_000_000L, "2026-01-01"));

    assertThat(spend.onExpenseEvent(Events.deleted(a, UUID.randomUUID(), 3,
        Events.state(400_000L, "2026-01-05", "general")))).isEqualTo(SpendService.Outcome.APPLIED);

    assertThat(spend(b, "2026-01-01")).as("floored, not negative").isZero();
  }

  @Test
  void theSameEventTwiceCountsOnce() {
    UUID a = UUID.randomUUID();
    UUID b = newBudget(a, budget("Everything", "monthly", null, 10_000_000L, "2026-01-01"));
    var event = Events.created(a, UUID.randomUUID(), Events.state(200_000L, "2026-01-09", "general"));

    assertThat(spend.onExpenseEvent(event)).isEqualTo(SpendService.Outcome.APPLIED);
    assertThat(spend.onExpenseEvent(event)).as("the inbox is what makes a delta exactly-once")
        .isEqualTo(SpendService.Outcome.DUPLICATE);

    assertThat(spend(b, "2026-01-01")).isEqualTo(200_000L);
  }

  @Test
  void eachThresholdAlertsAtMostOncePerPeriod() {
    UUID a = UUID.randomUUID();
    UUID b = newBudget(a, budget("Dining", "monthly", "food.dining", 1_000_000L, "2026-01-01"));

    // to half the limit: 50 % alerts
    spend.onExpenseEvent(Events.created(a, UUID.randomUUID(), Events.state(500_000L, "2026-01-02", "food.dining")));
    assertThat(alerted(b, "2026-01-01")).containsExactly(50);
    assertThat(outbox(a, "planning.budget.threshold_crossed")).hasSize(1);
    assertThat(outbox(a, "planning.budget.threshold_crossed").get(0))
        .contains("\"threshold\":50").contains("\"usedBps\":5000").contains("\"periodStart\":\"2026-01-01\"");

    // a little more: still only 50 % has been reached, and it does not alert again
    spend.onExpenseEvent(Events.created(a, UUID.randomUUID(), Events.state(100_000L, "2026-01-03", "food.dining")));
    assertThat(alerted(b, "2026-01-01")).containsExactly(50);
    assertThat(outbox(a, "planning.budget.threshold_crossed")).hasSize(1);

    // past 80 % and past the limit: both remaining thresholds alert, once each
    spend.onExpenseEvent(Events.created(a, UUID.randomUUID(), Events.state(500_000L, "2026-01-04", "food.dining")));
    assertThat(alerted(b, "2026-01-01")).containsExactly(50, 80, 100);
    assertThat(outbox(a, "planning.budget.threshold_crossed")).hasSize(3);

    // spend coming back down and going up again does not re-alert
    UUID expense = UUID.randomUUID();
    spend.onExpenseEvent(Events.created(a, expense, Events.state(100_000L, "2026-01-05", "food.dining")));
    spend.onExpenseEvent(Events.deleted(a, expense, 1, Events.state(100_000L, "2026-01-05", "food.dining")));
    assertThat(outbox(a, "planning.budget.threshold_crossed")).hasSize(3);

    // the next period starts clean
    spend.onExpenseEvent(Events.created(a, UUID.randomUUID(), Events.state(600_000L, "2026-02-02", "food.dining")));
    assertThat(alerted(b, "2026-02-01")).containsExactly(50);
    assertThat(outbox(a, "planning.budget.threshold_crossed")).hasSize(4);
  }

  @Test
  void aPausedOrEndedBudgetDoesNotTrack() {
    UUID a = UUID.randomUUID();
    UUID paused = newBudget(a, budget("Paused", "monthly", null, 1_000_000L, "2026-01-01"));
    patch("/v1/budgets/" + paused, a, "{\"status\":\"paused\"}", "\"0\"");

    spend.onExpenseEvent(Events.created(a, UUID.randomUUID(), Events.state(200_000L, "2026-01-11", "general")));

    assertThat(spend(paused, "2026-01-01")).isZero();
  }

  @Test
  void anExpenseOutsideEveryBudgetWindowChangesNothing() {
    UUID a = UUID.randomUUID();
    UUID b = newBudget(a, budget("This year", "monthly", null, 1_000_000L, "2026-01-01"));

    assertThat(spend.onExpenseEvent(Events.created(a, UUID.randomUUID(),
        Events.state(900_000L, "2025-06-01", "general")))).isEqualTo(SpendService.Outcome.APPLIED);

    assertThat(spend(b, "2025-06-01")).isZero();
    assertThat(ADMIN.sql("SELECT count(1) FROM planning.budget_period_spend WHERE budget_id = :b")
        .param("b", b).query(Long.class).single()).as("no row at all").isZero();
  }

  @Test
  void anEventWithNothingToApplyIsIgnored() {
    UUID a = UUID.randomUUID();
    newBudget(a, budget("Everything", "monthly", null, 1_000_000L, "2026-01-01"));

    assertThat(spend.onExpenseEvent(Events.expense("ledger.expense.updated", a, UUID.randomUUID(), 1, null, null)))
        .isEqualTo(SpendService.Outcome.IGNORED);
    assertThat(spend.onExpenseEvent(Events.expense("catalog.card.published", a, UUID.randomUUID(), 1, null,
        Events.state(1L, "2026-01-02", "general")))).isEqualTo(SpendService.Outcome.IGNORED);
  }

  @Test
  void anAccountWithNoBudgetsIsApplied() {
    UUID a = UUID.randomUUID();
    assertThat(spend.onExpenseEvent(Events.created(a, UUID.randomUUID(),
        Events.state(100_000L, "2026-01-02", "general")))).isEqualTo(SpendService.Outcome.APPLIED);
    assertThat(ADMIN.sql("SELECT count(1) FROM planning.budget_period_spend WHERE account_id = :a")
        .param("a", a).query(Long.class).single()).as("nothing to write to").isZero();
  }
}
