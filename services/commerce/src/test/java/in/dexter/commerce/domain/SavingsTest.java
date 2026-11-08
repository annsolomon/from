package in.dexter.commerce.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** MASTER_PLAN P17 golden: each component is capped separately against the same spend. */
class SavingsTest {
  static final SavingComponent TEN_CAP_5K = new SavingComponent.Percent(1000, 500_000, 0);
  static final SavingComponent FIVE_CAP_2K = new SavingComponent.Percent(500, 200_000, 0);

  @Test
  void goldenSeventyFiveThousandSavesSevenThousand() {
    Savings.Breakdown b = Savings.of(List.of(TEN_CAP_5K, FIVE_CAP_2K), 7_500_000);
    assertThat(b.lines()).extracting(Savings.Line::savingPaise).containsExactly(500_000L, 200_000L);
    assertThat(b.totalPaise()).isEqualTo(700_000);           // ₹7,000 — not ₹11,250 (15 % uncapped)
  }

  @Test
  void belowTheCapsEveryComponentIsItsRate() {
    // ₹20,000: 10 % = ₹2,000 (< ₹5,000 cap), 5 % = ₹1,000 (< ₹2,000 cap)
    assertThat(Savings.total(List.of(TEN_CAP_5K, FIVE_CAP_2K), 2_000_000)).isEqualTo(300_000);
  }

  @Test
  void flatAndMinimumSpend() {
    SavingComponent flat = new SavingComponent.Flat(25_000, 1_000_000);   // ₹250 off above ₹10,000
    assertThat(Savings.total(List.of(flat), 999_999)).isZero();
    assertThat(Savings.total(List.of(flat), 1_000_000)).isEqualTo(25_000);
    assertThat(Savings.total(List.of(new SavingComponent.Flat(25_000, 0)), 10_000)).isEqualTo(10_000);
  }

  @Test
  void neverMoreThanTheSpendAndRoundsDown() {
    SavingComponent all = new SavingComponent.Percent(10_000, SavingComponent.MAX_PAISE, 0);
    assertThat(Savings.total(List.of(all, new SavingComponent.Flat(500, 0)), 1_000)).isEqualTo(1_000);
    assertThat(new SavingComponent.Percent(333, 1_000_000, 0).savingFor(999)).isEqualTo(33); // 33.267 -> 33
    assertThat(Savings.total(List.of(TEN_CAP_5K), 0)).isZero();
  }

  @Test
  void rejectsBadComponents() {
    assertThatThrownBy(() -> new SavingComponent.Percent(0, 1, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SavingComponent.Percent(100, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SavingComponent.Flat(0, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Savings.of(List.of(), 1)).isInstanceOf(IllegalArgumentException.class);
  }
}
