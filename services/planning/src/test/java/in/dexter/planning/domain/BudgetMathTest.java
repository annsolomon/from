package in.dexter.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Where a budget period stands, where its rate is heading, and which thresholds it has reached. */
class BudgetMathTest {

  private static final List<Integer> THRESHOLDS = List.of(50, 80, 100);

  private static LocalDate d(String s) {
    return LocalDate.parse(s);
  }

  @Test
  void halfTheLimitSpentTenDaysIntoJanuary() {
    // limit Rs 10,000; spent Rs 5,000 by the 10th of a 31-day period
    BudgetMath.Status s = BudgetMath.status(1_000_000L, 500_000L, d("2026-01-01"), d("2026-01-31"),
        d("2026-01-10"), THRESHOLDS);

    assertThat(s.usedBps()).isEqualTo(5000);
    assertThat(s.remainingPaise()).isEqualTo(500_000L);
    assertThat(s.overLimit()).isFalse();
    assertThat(s.daysInPeriod()).isEqualTo(31);
    assertThat(s.daysElapsed()).as("today counts").isEqualTo(10);
    assertThat(s.projectedSpendPaise()).as("Rs 5,000 over 10 of 31 days").isEqualTo(1_550_000L);
    assertThat(s.projectedOverspendPaise()).isEqualTo(550_000L);
    assertThat(s.thresholdsCrossed()).containsExactly(50);
  }

  @Test
  void aThresholdIsReachedExactlyAtItsPercentage() {
    assertThat(BudgetMath.crossed(500_000L, 1_000_000L, List.of(50))).containsExactly(50);
    assertThat(BudgetMath.crossed(499_999L, 1_000_000L, List.of(50))).as("not a paisa earlier").isEmpty();
    assertThat(BudgetMath.crossed(1_200_000L, 1_000_000L, THRESHOLDS)).containsExactly(50, 80, 100);
    assertThat(BudgetMath.crossed(0L, 1_000_000L, THRESHOLDS)).isEmpty();
    assertThat(BudgetMath.crossed(100L, 1_000_000L, null)).isEmpty();
    assertThat(BudgetMath.crossed(1_000_000L, 1_000_000L, List.of(100, 50, 50)))
        .as("sorted and de-duplicated").containsExactly(50, 100);
  }

  @Test
  void overTheLimitIsReportedWithANegativeRemainder() {
    BudgetMath.Status s = BudgetMath.status(1_000_000L, 1_250_000L, d("2026-01-01"), d("2026-01-31"),
        d("2026-01-31"), THRESHOLDS);

    assertThat(s.overLimit()).isTrue();
    assertThat(s.remainingPaise()).isEqualTo(-250_000L);
    assertThat(s.usedBps()).isEqualTo(12_500);
    assertThat(s.projectedSpendPaise()).as("a finished period projects its own spend").isEqualTo(1_250_000L);
    assertThat(s.projectedOverspendPaise()).isEqualTo(250_000L);
  }

  @Test
  void theProjectionIsTheRateSoFar() {
    assertThat(BudgetMath.projectedSpend(500_000L, 10, 31)).isEqualTo(1_550_000L);
    assertThat(BudgetMath.projectedSpend(10_000L, 1, 31)).as("day one projects from one day").isEqualTo(310_000L);
    assertThat(BudgetMath.projectedSpend(900_000L, 31, 31)).as("a finished period is its own projection")
        .isEqualTo(900_000L);
    assertThat(BudgetMath.projectedSpend(0L, 5, 30)).isZero();
    assertThatThrownBy(() -> BudgetMath.projectedSpend(1L, 0, 30)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BudgetMath.projectedSpend(1L, 31, 30)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aDayBeforeThePeriodCountsAsDayOneAndALaterDayIsCapped() {
    assertThat(BudgetMath.status(1_000_000L, 0L, d("2026-02-01"), d("2026-02-28"), d("2026-01-20"), List.of())
        .daysElapsed()).isEqualTo(1);
    assertThat(BudgetMath.status(1_000_000L, 0L, d("2026-01-01"), d("2026-01-31"), d("2026-06-01"), List.of())
        .daysElapsed()).isEqualTo(31);
  }

  @Test
  void toAlertSkipsWhatHasAlreadyBeenSent() {
    assertThat(BudgetMath.toAlert(900_000L, 1_000_000L, THRESHOLDS, List.of(50))).containsExactly(80);
    assertThat(BudgetMath.toAlert(900_000L, 1_000_000L, THRESHOLDS, List.of(50, 80))).isEmpty();
    assertThat(BudgetMath.toAlert(900_000L, 1_000_000L, THRESHOLDS, null)).containsExactly(50, 80);
  }

  @Test
  void aNonPositiveLimitAndABackwardsPeriodAreRefused() {
    assertThatThrownBy(() -> BudgetMath.status(0L, 1L, d("2026-01-01"), d("2026-01-31"), d("2026-01-01"),
        THRESHOLDS)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
    assertThatThrownBy(() -> BudgetMath.status(1L, 1L, d("2026-01-31"), d("2026-01-01"), d("2026-01-01"),
        THRESHOLDS)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("periodEnd");
  }
}
