package in.dexter.reco.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The 3m/6m/12m windows, and the state hash that decides whether a recompute changed anything. */
class WindowsAndHashTest {
  static final LocalDate MAR15 = LocalDate.parse("2026-03-15");

  static SpendVector spend() {
    Map<String, Long> m = new LinkedHashMap<>();
    m.put("food", 2_000_000L);
    m.put("travel", 500_000L);
    return SpendVector.of(m);
  }

  @Test
  void aWindowEndsWithTheCurrentMonthAndReachesBack() {
    assertThat(Windows.start("3m", MAR15)).isEqualTo(LocalDate.parse("2026-01-01"));
    assertThat(Windows.start("6m", MAR15)).isEqualTo(LocalDate.parse("2025-10-01"));
    assertThat(Windows.start("12m", MAR15)).isEqualTo(LocalDate.parse("2025-04-01"));
  }

  @Test
  void membershipIsInclusiveOfBothEnds() {
    assertThat(Windows.contains("3m", MAR15, LocalDate.parse("2026-03-01"))).as("the current month").isTrue();
    assertThat(Windows.contains("3m", MAR15, LocalDate.parse("2026-01-01"))).as("the first month").isTrue();
    assertThat(Windows.contains("3m", MAR15, LocalDate.parse("2025-12-01"))).as("one month too early").isFalse();
    assertThat(Windows.contains("12m", MAR15, LocalDate.parse("2026-04-01"))).as("a future month").isFalse();
  }

  @Test
  void theAverageDividesByTheMonthsThatContributed() {
    // dividing by the window's length would make a profile built on the 2nd of a month a twelfth of the truth
    assertThat(Windows.monthlyAverage(150_000, 3)).isEqualTo(50_000);
    assertThat(Windows.monthlyAverage(150_000, 1)).isEqualTo(150_000);
    assertThat(Windows.monthlyAverage(150_000, 0)).isZero();
    assertThat(Windows.monthlyAverage(0, 3)).isZero();
  }

  @Test
  void aMonthIsTheFirstOfItsMonth() {
    assertThat(Windows.month(LocalDate.parse("2026-03-31"))).isEqualTo(LocalDate.parse("2026-03-01"));
    assertThat(Windows.month(LocalDate.parse("2026-03-01"))).isEqualTo(LocalDate.parse("2026-03-01"));
  }

  @Test
  void anUnknownWindowIsRefused() {
    assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> Windows.months("2m")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theHashIgnoresMapOrderButNotContent() {
    String a = StateHash.of(spend(), List.of("p1"), Map.of("p1", 1, "p2", 1), 6_000, 2_000);
    Map<String, Long> reversed = new LinkedHashMap<>();
    reversed.put("travel", 500_000L);
    reversed.put("food", 2_000_000L);
    String b = StateHash.of(SpendVector.of(reversed), List.of("p1"), Map.of("p2", 1, "p1", 1), 6_000, 2_000);
    assertThat(a).as("two equal profiles in different orders hash the same").isEqualTo(b);
    assertThat(a).matches("[0-9a-f]{64}");
  }

  @Test
  void everyInputMovesTheHash() {
    String base = StateHash.of(spend(), List.of("p1"), Map.of("p1", 1, "p2", 1), 6_000, 2_000);
    Map<String, Long> more = new LinkedHashMap<>();
    more.put("food", 2_000_001L);
    more.put("travel", 500_000L);
    assertThat(StateHash.of(SpendVector.of(more), List.of("p1"), Map.of("p1", 1, "p2", 1), 6_000, 2_000))
        .as("a rupee more spend").isNotEqualTo(base);
    assertThat(StateHash.of(spend(), List.of("p1"), Map.of("p1", 2, "p2", 1), 6_000, 2_000))
        .as("a republished card").isNotEqualTo(base);
    assertThat(StateHash.of(spend(), List.of("p1", "p2"), Map.of("p1", 1, "p2", 1), 6_000, 2_000))
        .as("a newly held card").isNotEqualTo(base);
    assertThat(StateHash.of(spend(), List.of("p1"), Map.of("p1", 1), 6_000, 2_000))
        .as("a card leaving the catalog").isNotEqualTo(base);
    assertThat(StateHash.of(spend(), List.of("p1"), Map.of("p1", 1, "p2", 1), 5_000, 2_000))
        .as("a different blend").isNotEqualTo(base);
    assertThat(StateHash.of(spend(), List.of("p1"), Map.of("p1", 1, "p2", 1), 6_000, 3_000))
        .as("a different threshold").isNotEqualTo(base);
  }

  @Test
  void theSpendVectorOrdersAndGuardsItself() {
    assertThat(spend().topRoot()).contains("food");
    assertThat(spend().monthlyTotal()).isEqualTo(2_500_000L);
    assertThat(spend().descending().keySet()).containsExactly("food", "travel");
    assertThat(spend().monthly("nothing")).isZero();
    assertThat(SpendVector.empty().isEmpty()).isTrue();
    assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> SpendVector.of(Map.of("food", -1L))))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
