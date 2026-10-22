package in.dexter.portfolio.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Statement cycles: statement day 1-28 or 31 (= month end), due date offset (pure). */
class BillingCycleTest {
  static BillingCycle c(String day, int statementDay, int due) {
    return BillingCycle.containing(LocalDate.parse(day), statementDay, due);
  }

  @Test
  void aDayBelongsToTheCycleEndingOnTheNextStatementDate() {
    assertThat(c("2026-10-22", 15, 20)).isEqualTo(new BillingCycle(LocalDate.parse("2026-10-16"),
        LocalDate.parse("2026-11-15"), LocalDate.parse("2026-12-05")));
    assertThat(c("2026-10-15", 15, 20).periodEnd()).as("the statement day closes its own cycle")
        .isEqualTo(LocalDate.parse("2026-10-15"));
    assertThat(c("2026-10-16", 15, 20).periodStart()).isEqualTo(LocalDate.parse("2026-10-16"));
    assertThat(c("2026-12-20", 15, 20).periodEnd()).as("across the year").isEqualTo(LocalDate.parse("2027-01-15"));
  }

  @Test
  void monthEndStatements() {
    assertThat(c("2026-02-28", 31, 18)).isEqualTo(new BillingCycle(LocalDate.parse("2026-02-01"),
        LocalDate.parse("2026-02-28"), LocalDate.parse("2026-03-18")));
    assertThat(c("2028-02-29", 31, 18).periodEnd()).as("leap year").isEqualTo(LocalDate.parse("2028-02-29"));
    assertThat(c("2026-03-01", 31, 18).periodEnd()).isEqualTo(LocalDate.parse("2026-03-31"));
    assertThat(c("2026-01-02", 1, 20)).isEqualTo(new BillingCycle(LocalDate.parse("2026-01-02"),
        LocalDate.parse("2026-02-01"), LocalDate.parse("2026-02-21")));
  }

  @Test
  void consecutiveCyclesTileTheCalendar() {
    for (int sd : new int[] {1, 7, 15, 28, 31}) {
      LocalDate d = LocalDate.parse("2026-01-01");
      BillingCycle prev = BillingCycle.containing(d, sd, 20);
      for (int i = 0; i < 400; i++, d = d.plusDays(1)) {
        BillingCycle c = BillingCycle.containing(d, sd, 20);
        assertThat(d).isBetween(c.periodStart(), c.periodEnd());
        if (!c.equals(prev)) {
          assertThat(c.periodStart()).as("no gap, no overlap").isEqualTo(prev.periodEnd().plusDays(1));
          prev = c;
        }
      }
    }
    assertThatThrownBy(() -> BillingCycle.containing(LocalDate.now(), 29, 20))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
