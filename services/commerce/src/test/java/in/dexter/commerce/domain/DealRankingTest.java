package in.dexter.commerce.domain;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.commerce.domain.DealRanking.CardMatch;
import in.dexter.commerce.domain.DealRanking.Candidate;
import in.dexter.commerce.domain.DealRanking.Ranked;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** score = 0.8·(0.6·cardMatch + 0.4·savingsNorm) + 0.2·sponsor, integer bps; card-match ranks, never filters. */
class DealRankingTest {
  static final UUID CARD_A = UUID.fromString("0192f1e2-0000-7000-8000-00000000a001");
  static final UUID CARD_B = UUID.fromString("0192f1e2-0000-7000-8000-00000000a002");

  static UUID id(int n) {
    return UUID.fromString(String.format("0192f1e2-0000-7000-8000-%012d", n));
  }

  @Test
  void weightsAreThePlansWeights() {
    assertThat(DealRanking.score(CardMatch.HELD, 10_000, true, 100)).isEqualTo(10_000);
    assertThat(DealRanking.score(CardMatch.HELD, 0, false, 0)).isEqualTo(4_800);      // 0.8 · 0.6
    assertThat(DealRanking.score(CardMatch.OTHER, 10_000, false, 0)).isEqualTo(3_200); // 0.8 · 0.4
    assertThat(DealRanking.score(CardMatch.OTHER, 0, true, 100)).isEqualTo(2_000);     // 0.2
    assertThat(DealRanking.score(CardMatch.OPEN, 5_000, true, 50)).isEqualTo(2_400 + 1_600 + 1_000);
  }

  @Test
  void cardMatchIsABranchNotAGate() {
    assertThat(DealRanking.match(List.of(CARD_A), Set.of(CARD_A))).isEqualTo(CardMatch.HELD);
    assertThat(DealRanking.match(List.of(), Set.of(CARD_A))).isEqualTo(CardMatch.OPEN);
    assertThat(DealRanking.match(List.of(CARD_B), Set.of(CARD_A))).isEqualTo(CardMatch.OTHER);
    List<Ranked> r = DealRanking.rank(List.of(
        new Candidate(id(1), 0, 100_000, CardMatch.OTHER, false, 0),
        new Candidate(id(2), 0, 50_000, CardMatch.HELD, false, 0)));
    assertThat(r).extracting(Ranked::id).containsExactly(id(2), id(1));   // both listed, the held card first
  }

  @Test
  void savingsAreNormalisedToTheBestInTheSet() {
    List<Ranked> r = DealRanking.rank(List.of(
        new Candidate(id(1), 0, 200_000, CardMatch.OPEN, false, 0),
        new Candidate(id(2), 0, 100_000, CardMatch.OPEN, false, 0)));
    assertThat(r).extracting(Ranked::scoreBps).containsExactly(2_400 + 3_200, 2_400 + 1_600);
  }

  @Test
  void ordersTotallyAndPagesWithoutRepeatsOrGaps() {
    List<Candidate> cs = new ArrayList<>();
    for (int i = 1; i <= 23; i++) {
      cs.add(new Candidate(id(i), i % 3, (i % 4) * 10_000L, i % 2 == 0 ? CardMatch.OPEN : CardMatch.OTHER,
          i % 5 == 0, i % 5 == 0 ? 40 : 0));
    }
    List<Ranked> ranked = DealRanking.rank(cs);
    List<UUID> seen = new ArrayList<>();
    DealRanking.Position after = null;
    while (true) {
      List<Ranked> page = DealRanking.page(ranked, after, 5);
      if (page.isEmpty()) {
        break;
      }
      page.forEach(p -> seen.add(p.id()));
      after = DealRanking.positionOf(page.get(page.size() - 1));
    }
    assertThat(seen).containsExactlyElementsOf(ranked.stream().map(Ranked::id).toList()).doesNotHaveDuplicates()
        .hasSize(23);
  }
}
