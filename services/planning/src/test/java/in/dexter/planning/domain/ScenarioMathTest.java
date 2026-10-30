package in.dexter.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.dexter.planning.domain.ScenarioMath.Adjustment;
import in.dexter.planning.domain.ScenarioMath.BudgetLine;
import in.dexter.planning.domain.ScenarioMath.LiabilityLine;
import in.dexter.planning.domain.ScenarioMath.Snapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What-if evaluation against the snapshot a scenario was saved with. Money is paise: ₹1 = 100. */
class ScenarioMathTest {

  /** Income ₹1,00,000 a month, a ₹30,000 food budget and a ₹20,000 instalment. */
  private static Snapshot base() {
    return new Snapshot(10_000_000L, List.of(new BudgetLine("b1", "Food", 3_000_000L)),
        List.of(new LiabilityLine("l1", "HDFC", 2_000_000L)));
  }

  @Test
  void withNoAdjustmentsTheOutgoIsTheSnapshotsOwn() {
    ScenarioMath.Result r = ScenarioMath.evaluate(base(), List.of());

    assertThat(r.monthlyIncomePaise()).isEqualTo(10_000_000L);
    assertThat(r.monthlyOutgoBeforePaise()).isEqualTo(5_000_000L);
    assertThat(r.monthlyOutgoAfterPaise()).isEqualTo(5_000_000L);
    assertThat(r.deltaPaise()).isZero();
    assertThat(r.surplusAfterPaise()).isEqualTo(5_000_000L);
    assertThat(r.feasible()).isTrue();
    assertThat(r.lines()).hasSize(2);
    assertThat(r.lines()).noneMatch(ScenarioMath.Line::added);
  }

  @Test
  void aHypotheticalLoanCostsTheSharedEnginesInstalment() {
    ScenarioMath.Result r = ScenarioMath.evaluate(base(),
        List.of(new Adjustment.AddLiability("Car loan", 50_000_000L, 1000, 60)));

    long emi = LiabilityMath.emi(50_000_000L, 1000, 60);
    assertThat(r.deltaPaise()).as("the same engine a real liability would use").isEqualTo(emi);
    assertThat(r.monthlyOutgoAfterPaise()).isEqualTo(5_000_000L + emi);
    assertThat(r.lines()).filteredOn(ScenarioMath.Line::added).hasSize(1);
    assertThat(r.lines()).filteredOn(ScenarioMath.Line::added).first()
        .extracting(ScenarioMath.Line::label).isEqualTo("Car loan");
  }

  @Test
  void removingALiabilityAndRaisingALimitMoveTheOutgo() {
    assertThat(ScenarioMath.evaluate(base(), List.of(new Adjustment.RemoveLiability("l1"))).deltaPaise())
        .isEqualTo(-2_000_000L);
    assertThat(ScenarioMath.evaluate(base(), List.of(new Adjustment.SetBudgetLimit("b1", 4_000_000L))).deltaPaise())
        .isEqualTo(1_000_000L);
    assertThat(ScenarioMath.evaluate(base(), List.of(new Adjustment.SetBudgetLimit("b1", 0L))).deltaPaise())
        .isEqualTo(-3_000_000L);
  }

  @Test
  void anIncomeBelowTheOutgoIsNotFeasibleAndTheShortfallIsReported() {
    ScenarioMath.Result r = ScenarioMath.evaluate(base(), List.of(new Adjustment.SetMonthlyIncome(4_000_000L)));

    assertThat(r.feasible()).isFalse();
    assertThat(r.surplusAfterPaise()).isEqualTo(-1_000_000L);
    assertThat(r.surplusBeforePaise()).as("before uses the snapshot's own income").isEqualTo(5_000_000L);
  }

  @Test
  void adjustmentsApplyInOrder() {
    ScenarioMath.Result r = ScenarioMath.evaluate(base(), List.of(
        new Adjustment.SetBudgetLimit("b1", 4_000_000L),
        new Adjustment.SetBudgetLimit("b1", 1_000_000L)));

    assertThat(r.monthlyOutgoAfterPaise()).isEqualTo(1_000_000L + 2_000_000L);
  }

  @Test
  void anAdjustmentToSomethingTheSnapshotDoesNotHaveIsRefused() {
    assertThatThrownBy(() -> ScenarioMath.evaluate(base(), List.of(new Adjustment.RemoveLiability("nope"))))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no liability");
    assertThatThrownBy(() -> ScenarioMath.evaluate(base(), List.of(new Adjustment.SetBudgetLimit("nope", 1L))))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no budget");
  }

  @Test
  void anAdjustmentMissingWhatItNeedsIsRefusedWhereItIsBuilt() {
    assertThatThrownBy(() -> new Adjustment.AddLiability("", 1L, 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Adjustment.AddLiability("Loan", 0L, 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Adjustment.SetBudgetLimit(null, 1L)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Adjustment.RemoveLiability(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Adjustment.SetMonthlyIncome(-1L)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Snapshot(-1L, List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anEmptySnapshotEvaluatesToTheIncomeItself() {
    ScenarioMath.Result r = ScenarioMath.evaluate(new Snapshot(5_000_000L, null, null), null);

    assertThat(r.monthlyOutgoAfterPaise()).isZero();
    assertThat(r.surplusAfterPaise()).isEqualTo(5_000_000L);
    assertThat(r.lines()).isEmpty();
  }
}
