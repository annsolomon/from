package in.dexter.core.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidsTest {
  @Test
  void ordersTheWayPostgresDoes() {
    // The high bit of the most significant half: unsigned says 8… > 0…, Java's signed compare says the opposite.
    UUID low = UUID.fromString("0fffffff-ffff-ffff-ffff-ffffffffffff");
    UUID high = UUID.fromString("80000000-0000-0000-0000-000000000000");
    assertThat(Uuids.compare(low, high)).isNegative();
    assertThat(low.compareTo(high)).isPositive();

    // Same for the variant bit of the least significant half (set on every RFC 9562 UUID).
    UUID a = UUID.fromString("0192f0c1-7d3e-7abc-0def-000000000001");
    UUID b = UUID.fromString("0192f0c1-7d3e-7abc-8def-000000000001");
    assertThat(Uuids.compare(a, b)).isNegative();
    assertThat(a.compareTo(b)).isPositive();
    assertThat(Uuids.compare(a, a)).isZero();
  }

  @Test
  void sortsAsAnAscendingComparator() {
    UUID a = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID b = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
    UUID c = UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffffe");
    assertThat(List.of(c, a, b).stream().sorted(Uuids.POSTGRES_ORDER)).containsExactly(a, b, c);
  }
}
