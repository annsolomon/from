package in.dexter.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RngTest {

  @Test
  void matchesTheSplitMix64ReferenceStream() {
    // Reference SplitMix64 (Steele/Lea/Flood; Vigna's splitmix64.c), seed 42.
    Rng r = new Rng(42);
    assertThat(r.nextLong()).isEqualTo(0xbdd732262feb6e95L);
    assertThat(r.nextLong()).isEqualTo(0x28efe333b266f103L);
    assertThat(r.nextLong()).isEqualTo(0x47526757130f9f52L);
  }

  @Test
  void forksDependOnTheLabelNotOnCallOrder() {
    Rng a = new Rng(42);
    Rng b = new Rng(42);
    b.nextLong();
    b.nextLong(); // consuming the parent must not change its forks
    assertThat(a.fork("account:asha@demo.in").nextLong()).isEqualTo(b.fork("account:asha@demo.in").nextLong())
        .isEqualTo(0xe26d64a5fa9dec64L);
    assertThat(a.fork("x").nextLong()).isNotEqualTo(a.fork("y").nextLong());
    assertThat(new Rng(42).fork("x").nextLong()).isNotEqualTo(new Rng(43).fork("x").nextLong());
  }

  @Test
  void boundedDrawsStayInRangeAndCoverIt() {
    Rng r = new Rng(7);
    int[] seen = new int[7];
    for (int i = 0; i < 70_000; i++) {
      int v = r.nextInt(7);
      assertThat(v).isBetween(0, 6);
      seen[v]++;
    }
    for (int c : seen) {
      assertThat(c).isBetween(9_000, 11_000);
    }
    for (int i = 0; i < 1_000; i++) {
      assertThat(r.between(-3, 3)).isBetween(-3, 3);
      assertThat(r.unit()).isGreaterThanOrEqualTo(0.0).isLessThan(1.0);
    }
    assertThatThrownBy(() -> r.nextInt(0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void weightedNeverPicksAZeroWeight() {
    Rng r = new Rng(9);
    long[] cum = {0, 5, 5, 10}; // weights 0, 5, 0, 5
    int[] seen = new int[4];
    for (int i = 0; i < 10_000; i++) {
      seen[r.weighted(cum)]++;
    }
    assertThat(seen[0]).isZero();
    assertThat(seen[2]).isZero();
    assertThat(seen[1]).isBetween(4_500, 5_500);
  }

  @Test
  void logNormalHasTheRequestedMedian() {
    Rng r = new Rng(11);
    int below = 0;
    for (int i = 0; i < 20_000; i++) {
      if (r.logNormal(1000, 0.7) < 1000) {
        below++;
      }
    }
    assertThat(below).isBetween(9_600, 10_400);
  }

  @Test
  void uuidV7CarriesTheTimestampVersionAndVariant() {
    UUID id = SeedIds.v7(1_700_000_000_000L, new Rng(1));
    assertThat(id.toString()).isEqualTo("018bcfe5-6800-7cc1-beeb-8da1658eec67");
    assertThat(id.version()).isEqualTo(7);
    assertThat(id.variant()).isEqualTo(2);
    assertThat(SeedIds.millisOf(id)).isEqualTo(1_700_000_000_000L);
    assertThat(SeedIds.v7(1_700_000_000_001L, new Rng(1)).toString()).isGreaterThan(id.toString());
    assertThatThrownBy(() -> SeedIds.v7(-1, new Rng(1))).isInstanceOf(IllegalArgumentException.class);
  }
}
