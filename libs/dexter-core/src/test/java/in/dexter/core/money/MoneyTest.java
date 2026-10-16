package in.dexter.core.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoneyTest {

  /** paise x bps / 10000 with HALF_EVEN. The .5 rows prove banker's rounding (to the even neighbour). */
  @ParameterizedTest(name = "{0} paise x {1} bps = {2}")
  @CsvSource({
      "10000, 150, 150",
      "100, 50, 0",
      "300, 50, 2",
      "500, 50, 2",
      "700, 50, 4",
      "-500, 50, -2",
      "12345, 175, 216",
      "7500000, 1000, 750000",
      "99, 3333, 33",
      "1, 10000, 1",
      "123456789, 4200, 51851851"
  })
  void timesBpsHalfEven(long paise, int bps, long expected) {
    assertThat(Money.ofPaise(paise).times(Bps.of(bps)).paise()).isEqualTo(expected);
  }

  @Test
  void otherRoundingModes() {
    assertThat(Money.ofPaise(500).times(Bps.of(50), RoundingMode.HALF_UP).paise()).isEqualTo(3);
    assertThat(Money.ofPaise(501).times(Bps.of(50), RoundingMode.FLOOR).paise()).isEqualTo(2);
    assertThat(Money.ofPaise(501).times(Bps.of(50), RoundingMode.CEILING).paise()).isEqualTo(3);
  }

  @Test
  void parsesRupeesExactlyAndRejectsSubPaise() {
    assertThat(Money.ofRupees("1234.5").paise()).isEqualTo(123450);
    assertThat(Money.ofRupees("0.01").paise()).isEqualTo(1);
    assertThatThrownBy(() -> Money.ofRupees("1.005")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void arithmeticIsOverflowChecked() {
    Money max = Money.ofPaise(Long.MAX_VALUE);
    assertThatThrownBy(() -> max.plus(Money.ofPaise(1))).isInstanceOf(ArithmeticException.class);
    assertThatThrownBy(() -> max.times(2)).isInstanceOf(ArithmeticException.class);
    assertThat(Money.ofPaise(5).minus(Money.ofPaise(7)).paise()).isEqualTo(-2);
  }

  @Test
  void currenciesNeverMix() {
    assertThatThrownBy(() -> Money.ofPaise(1).plus(new Money(1, "USD"))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Money(1, "inr")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void allocateSumsExactly() {
    Money[] parts = Money.ofPaise(1000).allocate(3);
    assertThat(Arrays.stream(parts).mapToLong(Money::paise)).containsExactly(334L, 333L, 333L);
    Money[] neg = Money.ofPaise(-1000).allocate(3);
    assertThat(Arrays.stream(neg).mapToLong(Money::paise).sum()).isEqualTo(-1000);
  }

  @Test
  void fractionAndDisplay() {
    assertThat(Money.ofPaise(1000).timesFraction(new BigDecimal("0.3333"), RoundingMode.HALF_EVEN).paise()).isEqualTo(333);
    assertThat(Money.ofPaise(123450).toString()).isEqualTo("INR 1,234.50");
    assertThat(Money.ofPaise(123450).toMajor()).isEqualByComparingTo("1234.50");
  }
}
