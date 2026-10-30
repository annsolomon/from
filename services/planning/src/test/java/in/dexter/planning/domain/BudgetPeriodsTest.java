package in.dexter.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.planning.domain.BudgetPeriods.Period;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Which period a day falls in, and the category containment a budget matches with (MASTER_PLAN P13). A budget
 * anchored on the 1st has calendar months, which is what the plan's IST boundary case asks about: the ledger stamps
 * an expense at 23:30 IST on the 31st as day "the 31st", so it lands in that month.
 */
class BudgetPeriodsTest {

  private static LocalDate d(String s) {
    return LocalDate.parse(s);
  }

  private static String start(Period p, String startsOn, String endsOn, String day) {
    return BudgetPeriods.periodStart(p, d(startsOn), endsOn == null ? null : d(endsOn), d(day))
        .map(Object::toString).orElse("none");
  }

  @Test
  void aMonthlyBudgetFromTheFirstHasCalendarMonths() {
    assertThat(start(Period.MONTHLY, "2026-01-01", null, "2026-01-01")).isEqualTo("2026-01-01");
    // 23:30 IST on the 31st arrives as the 31st: January, not February
    assertThat(start(Period.MONTHLY, "2026-01-01", null, "2026-01-31")).isEqualTo("2026-01-01");
    assertThat(start(Period.MONTHLY, "2026-01-01", null, "2026-02-01")).isEqualTo("2026-02-01");
    assertThat(start(Period.MONTHLY, "2026-01-01", null, "2026-12-15")).isEqualTo("2026-12-01");
    assertThat(start(Period.MONTHLY, "2026-01-01", null, "2027-01-03")).isEqualTo("2027-01-01");
    assertThat(BudgetPeriods.periodEnd(Period.MONTHLY, d("2026-01-01"), null, d("2026-01-01")))
        .isEqualTo(d("2026-01-31"));
    assertThat(BudgetPeriods.periodEnd(Period.MONTHLY, d("2026-01-01"), null, d("2026-02-01")))
        .as("a short month ends when it ends").isEqualTo(d("2026-02-28"));
  }

  @Test
  void aMonthlyBudgetAnchoredOnTheThirtyFirstClampsThroughFebruary() {
    assertThat(start(Period.MONTHLY, "2026-01-31", null, "2026-01-31")).isEqualTo("2026-01-31");
    assertThat(start(Period.MONTHLY, "2026-01-31", null, "2026-02-27")).isEqualTo("2026-01-31");
    assertThat(start(Period.MONTHLY, "2026-01-31", null, "2026-02-28")).isEqualTo("2026-02-28");
    assertThat(start(Period.MONTHLY, "2026-01-31", null, "2026-03-15")).isEqualTo("2026-02-28");
    assertThat(start(Period.MONTHLY, "2026-01-31", null, "2026-03-31")).isEqualTo("2026-03-31");
    assertThat(BudgetPeriods.periodEnd(Period.MONTHLY, d("2026-01-31"), null, d("2026-01-31")))
        .isEqualTo(d("2026-02-27"));
    assertThat(BudgetPeriods.previousPeriodStart(Period.MONTHLY, d("2026-01-31"), d("2026-02-28")))
        .contains(d("2026-01-31"));
    assertThat(BudgetPeriods.previousPeriodStart(Period.MONTHLY, d("2026-01-31"), d("2026-01-31")))
        .as("nothing before the first period").isEmpty();
  }

  @Test
  void weeklyPeriodsRunSevenDaysFromTheStart() {
    assertThat(start(Period.WEEKLY, "2026-01-05", null, "2026-01-05")).isEqualTo("2026-01-05");
    assertThat(start(Period.WEEKLY, "2026-01-05", null, "2026-01-11")).isEqualTo("2026-01-05");
    assertThat(start(Period.WEEKLY, "2026-01-05", null, "2026-01-12")).isEqualTo("2026-01-12");
    assertThat(BudgetPeriods.periodEnd(Period.WEEKLY, d("2026-01-05"), null, d("2026-01-05")))
        .isEqualTo(d("2026-01-11"));
    assertThat(BudgetPeriods.previousPeriodStart(Period.WEEKLY, d("2026-01-05"), d("2026-01-12")))
        .contains(d("2026-01-05"));
  }

  @Test
  void aCustomBudgetIsOnePeriodAndTheWindowIsRespected() {
    assertThat(start(Period.CUSTOM, "2026-01-10", "2026-02-20", "2026-02-02")).isEqualTo("2026-01-10");
    assertThat(start(Period.CUSTOM, "2026-01-10", "2026-02-20", "2026-02-21")).isEqualTo("none");
    assertThat(start(Period.CUSTOM, "2026-01-10", "2026-02-20", "2026-01-09")).isEqualTo("none");
    assertThat(BudgetPeriods.periodEnd(Period.CUSTOM, d("2026-01-10"), d("2026-02-20"), d("2026-01-10")))
        .isEqualTo(d("2026-02-20"));
    assertThat(BudgetPeriods.previousPeriodStart(Period.CUSTOM, d("2026-01-10"), d("2026-01-10"))).isEmpty();
  }

  @Test
  void aDayOutsideTheWindowBelongsToNoPeriod() {
    assertThat(start(Period.MONTHLY, "2026-01-01", null, "2025-12-31")).isEqualTo("none");
    assertThat(start(Period.MONTHLY, "2026-01-01", "2026-03-31", "2026-04-01")).isEqualTo("none");
    assertThat(BudgetPeriods.periodEnd(Period.MONTHLY, d("2026-01-01"), d("2026-01-20"), d("2026-01-01")))
        .as("the period cannot outlive the budget").isEqualTo(d("2026-01-20"));
    assertThat(Optional.of(Period.of("weekly"))).contains(Period.WEEKLY);
    assertThat(Period.MONTHLY.stored()).isEqualTo("monthly");
  }

  @Test
  void aBudgetMatchesItsCategoryAndEverythingBelowIt() {
    assertThat(Categories.matches(null, "food.dining")).as("no category = all spend").isTrue();
    assertThat(Categories.matches("food", "food")).isTrue();
    assertThat(Categories.matches("food", "food.dining")).isTrue();
    assertThat(Categories.matches("food", "food.dining.fastfood")).isTrue();
    assertThat(Categories.matches("food", "foodcourt")).as("a prefix is not a parent").isFalse();
    assertThat(Categories.matches("food", "travel")).isFalse();
    assertThat(Categories.matches("food", null)).isFalse();
  }
}
