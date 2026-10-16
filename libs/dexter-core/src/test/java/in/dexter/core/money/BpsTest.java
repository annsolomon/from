package in.dexter.core.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BpsTest {
  @Test
  void percentParsing() {
    assertThat(Bps.ofPercent("1.5").value()).isEqualTo(150);
    assertThat(Bps.ofPercent("42").value()).isEqualTo(4200);
    assertThat(Bps.ofPercent("0.01").value()).isEqualTo(1);
    assertThatThrownBy(() -> Bps.ofPercent("0.015")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rangeAndFraction() {
    assertThatThrownBy(() -> Bps.of(1_000_001)).isInstanceOf(IllegalArgumentException.class);
    assertThat(Bps.of(150).asFraction()).isEqualByComparingTo("0.015");
    assertThat(Bps.of(150).plus(Bps.of(50)).value()).isEqualTo(200);
    assertThat(Bps.of(150).toString()).isEqualTo("1.5% (150 bps)");
  }
}
