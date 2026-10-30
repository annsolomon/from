package in.dexter.planning;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.planning.app.SpendService;
import in.dexter.planning.domain.BudgetPeriods;
import in.dexter.planning.domain.BudgetPeriods.Period;
import in.dexter.planning.domain.Categories;
import in.dexter.planning.support.Events;
import in.dexter.planning.support.PlanningIT;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * MASTER_PLAN P13's property test: after a long random run of expense events, every stored period spend equals the
 * sum recomputed independently from the expenses that are live at the end.
 *
 * <p>The projection applies deltas, so this is the test that it never drifts: 600 creates, edits (amount, day and
 * category), deletes and restores against three budgets of different shapes, then one comparison per period.
 */
class SpendPropertyIT extends PlanningIT {

  private static final int OPERATIONS = 600;
  private static final String[] CATEGORIES = {"food.dining", "food.delivery", "travel.cab", "travel", "general",
      "shopping.online"};
  private static final LocalDate FROM = LocalDate.parse("2026-01-01");

  /** One expense as the test believes it to be. */
  private record Expense(UUID id, long amountPaise, LocalDate day, String category, long version, boolean live) {}

  /** A budget as the test believes it to be. */
  private record Budget(UUID id, Period period, String category, LocalDate startsOn) {}

  @Autowired
  SpendService spend;

  @Test
  void everyPeriodSpendEqualsARecomputedSum() {
    UUID a = UUID.randomUUID();
    List<Budget> budgets = List.of(
        new Budget(newBudget(a, budget("Everything", "monthly", null, 100_000_000L, "2026-01-01")),
            Period.MONTHLY, null, FROM),
        new Budget(newBudget(a, budget("Food", "monthly", "food", 100_000_000L, "2026-01-01")),
            Period.MONTHLY, "food", FROM),
        new Budget(newBudget(a, budget("Weekly travel", "weekly", "travel", 100_000_000L, "2026-01-01")),
            Period.WEEKLY, "travel", FROM));

    Random random = new Random(13_031_300L);
    List<Expense> expenses = new ArrayList<>();

    for (int op = 0; op < OPERATIONS; op++) {
      int choice = random.nextInt(100);
      if (expenses.isEmpty() || choice < 55) {
        Expense e = new Expense(UUID.randomUUID(), 1_000L + random.nextInt(900_000), day(random),
            CATEGORIES[random.nextInt(CATEGORIES.length)], 0, true);
        expenses.add(e);
        apply(a, Events.created(a, e.id(), state(e)));
      } else {
        int at = random.nextInt(expenses.size());
        Expense e = expenses.get(at);
        if (choice < 80) {
          // an edit: a new amount, day or category, one at a time so each kind of move is exercised
          Expense after = switch (random.nextInt(3)) {
            case 0 -> new Expense(e.id(), 1_000L + random.nextInt(900_000), e.day(), e.category(),
                e.version() + 1, e.live());
            case 1 -> new Expense(e.id(), e.amountPaise(), day(random), e.category(), e.version() + 1, e.live());
            default -> new Expense(e.id(), e.amountPaise(), e.day(),
                CATEGORIES[random.nextInt(CATEGORIES.length)], e.version() + 1, e.live());
          };
          if (e.live()) {
            apply(a, Events.updated(a, e.id(), after.version(), state(e), state(after)));
            expenses.set(at, after);
          }
        } else if (choice < 92) {
          if (e.live()) {
            apply(a, Events.deleted(a, e.id(), e.version() + 1, state(e)));
            expenses.set(at, new Expense(e.id(), e.amountPaise(), e.day(), e.category(), e.version() + 1, false));
          }
        } else if (!e.live()) {
          Expense back = new Expense(e.id(), e.amountPaise(), e.day(), e.category(), e.version() + 1, true);
          apply(a, Events.restored(a, e.id(), back.version(), state(back)));
          expenses.set(at, back);
        }
      }
    }

    // recomputed independently from what is live at the end
    Map<String, Long> expected = new HashMap<>();
    for (Expense e : expenses) {
      if (!e.live()) {
        continue;
      }
      for (Budget b : budgets) {
        if (!Categories.matches(b.category(), e.category())) {
          continue;
        }
        BudgetPeriods.periodStart(b.period(), b.startsOn(), null, e.day())
            .ifPresent(start -> expected.merge(b.id() + "|" + start, e.amountPaise(), Long::sum));
      }
    }

    List<Map<String, Object>> stored = ADMIN.sql("SELECT budget_id::text AS b, period_start::text AS p,"
            + " spent_paise FROM planning.budget_period_spend WHERE account_id = :a")
        .param("a", a).query().listOfRows();

    long live = expenses.stream().filter(Expense::live).count();
    assertThat(live).as("the run left expenses behind to compare").isGreaterThan(50);
    assertThat(stored).as("and periods to compare them against").isNotEmpty();

    for (Map<String, Object> row : stored) {
      String key = row.get("b") + "|" + row.get("p");
      long spent = ((Number) row.get("spent_paise")).longValue();
      assertThat(spent).as("stored spend of " + key).isEqualTo(expected.getOrDefault(key, 0L));
    }
    // and nothing the recomputation expects is missing from the table
    for (Map.Entry<String, Long> e : expected.entrySet()) {
      boolean found = stored.stream().anyMatch(r -> (r.get("b") + "|" + r.get("p")).equals(e.getKey()));
      assertThat(found).as("a period the recomputation expects: " + e.getKey()).isTrue();
    }
    assertThat(ADMIN.sql("SELECT count(1) FROM planning.budget_period_spend WHERE account_id = :a"
            + " AND spent_paise < 0").param("a", a).query(Long.class).single())
        .as("no row ever went negative").isZero();
  }

  private void apply(UUID account, in.dexter.events.EventEnvelope e) {
    assertThat(spend.onExpenseEvent(e)).isEqualTo(SpendService.Outcome.APPLIED);
  }

  /** Days across three months, so monthly and weekly periods both see several. */
  private static LocalDate day(Random random) {
    return FROM.plusDays(random.nextInt(90));
  }

  private static in.dexter.contracts.events.ledger.ExpenseStateV1 state(Expense e) {
    return Events.state(e.amountPaise(), e.day().toString(), e.category());
  }
}
