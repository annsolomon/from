package in.dexter.reco.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.dexter.reco.domain.CardTerms.Milestone;
import in.dexter.reco.domain.CardTerms.Rule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The wallet optimizer (MASTER_PLAN P15), including the plan's named test: golden cases against a brute force.
 *
 * <p>The brute force here is deliberately naive — every subset of every size, every routing of every root, valued
 * with {@link Savings#of} — and it is the point of this class. The optimizer's search has three pieces of
 * cleverness (a redundancy report, a beam, and a contested/uncontested split of the routing) and each one is a
 * chance to be subtly wrong. Three times during P15 this comparison caught exactly that (ADR-0021 §3).
 */
class OptimizerTest {

  static Rule rule(String id, String path, String type, int rateBps, long pointValue, Long cap) {
    return new Rule(id, path, null, type, rateBps, pointValue, cap, cap == null ? null : "month", 0, List.of(), 0);
  }

  static CardTerms card(String id, long fee, Long waiver, List<Rule> rules, Milestone ms) {
    return new CardTerms(id, "slug-" + id, "Card " + id, "ISS", fee, waiver, rules, ms);
  }

  static SpendVector spend(Object... kv) {
    Map<String, Long> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], ((Number) kv[i + 1]).longValue());
    }
    return SpendVector.of(m);
  }

  // ------------------------------------------------------------------------------- the reference brute force

  /** The best total over every routing of every root to one of the wallet's cards. */
  static long bruteWalletValue(List<CardTerms> wallet, SpendVector spend) {
    List<String> roots = List.copyOf(spend.descending().keySet());
    int k = wallet.size();
    int n = roots.size();
    long best = Long.MIN_VALUE;
    long routings = (long) Math.pow(k, n);
    for (long code = 0; code < routings; code++) {
      long rest = code;
      List<Set<String>> sets = new ArrayList<>();
      for (int i = 0; i < k; i++) {
        sets.add(new LinkedHashSet<>());
      }
      for (int i = 0; i < n; i++) {
        sets.get((int) (rest % k)).add(roots.get(i));
        rest /= k;
      }
      long total = 0;
      for (int i = 0; i < k; i++) {
        total += Savings.of(wallet.get(i), spend.only(sets.get(i))).annualSavingsPaise();
      }
      best = Math.max(best, total);
    }
    return best;
  }

  static long bruteBestOfSize(List<CardTerms> cards, SpendVector spend, int size) {
    long best = Long.MIN_VALUE;
    for (List<CardTerms> combo : subsets(cards, size)) {
      best = Math.max(best, bruteWalletValue(combo, spend));
    }
    return best;
  }

  static List<List<CardTerms>> subsets(List<CardTerms> cards, int size) {
    List<List<CardTerms>> out = new ArrayList<>();
    pick(cards, size, 0, new ArrayList<>(), out);
    return out;
  }

  private static void pick(List<CardTerms> cards, int size, int from, List<CardTerms> acc,
      List<List<CardTerms>> out) {
    if (acc.size() == size) {
      out.add(List.copyOf(acc));
      return;
    }
    for (int i = from; i < cards.size(); i++) {
      acc.add(cards.get(i));
      pick(cards, size, i + 1, acc, out);
      acc.remove(acc.size() - 1);
    }
  }

  // ------------------------------------------------------------------------------- the plan's named test

  @Test
  void everyWalletMatchesABruteForceOverTheWholeCandidateSet() {
    Random rnd = new Random(20261101L);
    String[] roots = {"food", "travel", "bills", "shopping", "health", "gifts"};
    int compared = 0;
    for (int it = 0; it < 60; it++) {
      int nCards = 2 + rnd.nextInt(4);
      int nRoots = 1 + rnd.nextInt(roots.length);
      List<CardTerms> cards = new ArrayList<>();
      for (int i = 0; i < nCards; i++) {
        List<Rule> rules = new ArrayList<>();
        for (int j = 0; j < 1 + rnd.nextInt(2); j++) {
          String path = rnd.nextInt(4) == 0 ? null : roots[rnd.nextInt(nRoots)];
          Long cap = rnd.nextInt(3) == 0 ? null : (long) (10_000 + rnd.nextInt(80_000));
          rules.add(rule("r" + i + "_" + j, path, rnd.nextInt(4) == 0 ? "points" : "cashback",
              100 + rnd.nextInt(900), rnd.nextInt(4) == 0 ? 250 : 0, cap));
        }
        Milestone ms = rnd.nextInt(4) == 0
            ? new Milestone(5_000_000L + rnd.nextInt(5_000_000), "year", "voucher", 50_000L) : null;
        cards.add(card("c" + i, rnd.nextInt(3) == 0 ? 0 : 50_000L + rnd.nextInt(200_000),
            rnd.nextInt(2) == 0 ? null : 10_000_000L + rnd.nextInt(30_000_000), rules, ms));
      }
      Map<String, Long> sm = new LinkedHashMap<>();
      for (int i = 0; i < nRoots; i++) {
        sm.put(roots[i], 100_000L + rnd.nextInt(3_000_000));
      }
      SpendVector s = SpendVector.of(sm);

      Optimizer.Result r = Optimizer.optimize(cards, s, Set.of(), 3);
      for (Optimizer.Wallet w : r.bySize()) {
        if (!w.exact()) {
          continue;                               // the greedy fallback is not claimed to be optimal
        }
        assertThat(w.annualValuePaise()).as("size %d, iteration %d", w.size(), it)
            .isEqualTo(bruteBestOfSize(cards, s, w.size()));
        compared++;

        Set<String> seen = new LinkedHashSet<>();
        for (Optimizer.Seat seat : w.seats()) {
          for (String root : seat.categoryRoots()) {
            assertThat(seen.add(root)).as("a rupee is spent once (iteration %d)", it).isTrue();
          }
          for (Savings.Line l : seat.savings().lines()) {
            assertThat(l.annualCapPaise() == null || l.annualRewardPaise() <= l.annualCapPaise())
                .as("no line exceeds its cap (iteration %d)", it).isTrue();
          }
        }
        assertThat(w.seats().stream().mapToLong(Optimizer.Seat::annualValuePaise).sum())
            .as("the seats sum to the wallet").isEqualTo(w.annualValuePaise());
      }
    }
    assertThat(compared).as("the comparison actually ran").isGreaterThan(100);
  }

  // ------------------------------------------------------------------------------- golden cases

  @Test
  void aRupeeIsSpentOnce() {
    CardTerms a = card("a", 0, null, List.of(rule("ra", "food", "cashback", 500, 0, null),
        rule("ra2", "travel", "cashback", 100, 0, null)), null);
    CardTerms b = card("b", 0, null, List.of(rule("rb", "food", "cashback", 100, 0, null),
        rule("rb2", "travel", "cashback", 500, 0, null)), null);
    SpendVector s = spend("food", 1_000_000L, "travel", 1_000_000L);

    Optimizer.Result r = Optimizer.optimize(List.of(a, b), s, Set.of(), 2);
    Optimizer.Wallet two = r.bySize().get(1);
    assertThat(two.routing()).containsEntry("food", "a").containsEntry("travel", "b");
    assertThat(two.annualValuePaise()).as("5 % on both roots, not 6 % on each card").isEqualTo(1_200_000L);
    assertThat(two.seats().stream().mapToLong(x -> x.categoryRoots().size()).sum()).isEqualTo(2);
  }

  @Test
  void aSecondCardBringsASecondCap() {
    // two identical catch-all cards, 5 % capped at Rs 500 a month each, over two roots of Rs 20,000
    CardTerms c = card("c", 0, null, List.of(rule("rc", null, "cashback", 500, 0, 50_000L)), null);
    CardTerms d = card("d", 0, null, List.of(rule("rd", null, "cashback", 500, 0, 50_000L)), null);
    SpendVector s = spend("food", 2_000_000L, "travel", 2_000_000L);

    Optimizer.Result r = Optimizer.optimize(List.of(c, d), s, Set.of(), 2);
    assertThat(r.dropped()).as("a capped duplicate is never called redundant - its cap is the point").isEmpty();
    assertThat(r.bySize().get(0).annualValuePaise()).isEqualTo(600_000L);
    assertThat(r.bySize().get(1).annualValuePaise()).isEqualTo(1_200_000L);
    assertThat(r.bySize().get(1).marginalValuePaise()).as("worth exactly the second cap").isEqualTo(600_000L);
  }

  @Test
  void anUncappedDuplicateIsReportedRedundantButStillSearched() {
    CardTerms e = card("e", 0, null, List.of(rule("re", null, "cashback", 500, 0, null)), null);
    CardTerms f = card("f", 0, null, List.of(rule("rf", null, "cashback", 500, 0, null)), null);
    SpendVector s = spend("food", 2_000_000L, "travel", 2_000_000L);

    Optimizer.Result r = Optimizer.optimize(List.of(e, f), s, Set.of(), 2);
    assertThat(r.dropped()).as("the report names it").contains("f");
    assertThat(r.bySize()).as("but the search still considers it (ADR-0021 §3)").hasSize(2);
    assertThat(r.bySize().get(0).annualValuePaise()).isEqualTo(2_400_000L);
    assertThat(r.bySize().get(1).marginalValuePaise()).as("and it adds nothing, which is what redundant means")
        .isZero();
  }

  @Test
  void splittingAWalletCostsWaiversAndAnIdleCardStillCostsItsFee() {
    // a earns more but charges Rs 1,000 waived at Rs 2,40,000; b earns less and charges Rs 500 never waived
    CardTerms a = card("a", 100_000L, 24_000_000L, List.of(rule("ra", null, "cashback", 500, 0, null)), null);
    CardTerms b = card("b", 50_000L, null, List.of(rule("rb", null, "cashback", 400, 0, null)), null);
    SpendVector s = spend("food", 1_000_000L, "travel", 1_000_000L);

    assertThat(Savings.of(a, s).fee().expectedFeePaise()).as("alone, a clears its waiver").isZero();

    Optimizer.Result r = Optimizer.optimize(List.of(a, b), s, Set.of(), 2);
    assertThat(r.dropped()).as("a bigger sticker fee that gets waived is not dominated").isEmpty();
    assertThat(r.bySize().get(0).annualValuePaise()).isEqualTo(1_200_000L);

    Optimizer.Wallet two = r.bySize().get(1);
    assertThat(two.routing().values()).as("everything stays on a rather than splitting below its waiver")
        .containsOnly("a");
    assertThat(two.annualFeePaise()).as("b is idle and still pays its own fee").isEqualTo(50_000L);
    assertThat(two.marginalValuePaise()).isEqualTo(-50_000L);
    assertThat(r.best().size()).as("so the answer is the single card").isEqualTo(1);
  }

  @Test
  void marginalValueIsTheDifferenceFromOneCardFewer() {
    CardTerms a = card("a", 0, null, List.of(rule("ra", "food", "cashback", 500, 0, null)), null);
    CardTerms b = card("b", 0, null, List.of(rule("rb", "travel", "cashback", 500, 0, null)), null);
    CardTerms c = card("c", 0, null, List.of(rule("rc", "bills", "cashback", 500, 0, null)), null);
    SpendVector s = spend("food", 1_000_000L, "travel", 800_000L, "bills", 600_000L);

    Optimizer.Result r = Optimizer.optimize(List.of(a, b, c), s, Set.of(), 3);
    assertThat(r.bySize()).hasSize(3);
    long v1 = r.bySize().get(0).annualValuePaise();
    long v2 = r.bySize().get(1).annualValuePaise();
    long v3 = r.bySize().get(2).annualValuePaise();
    assertThat(v1).isEqualTo(600_000L);
    assertThat(v2).isEqualTo(1_080_000L);
    assertThat(v3).isEqualTo(1_440_000L);
    assertThat(r.bySize().get(0).marginalValuePaise()).isZero();
    assertThat(r.bySize().get(1).marginalValuePaise()).isEqualTo(v2 - v1);
    assertThat(r.bySize().get(2).marginalValuePaise()).isEqualTo(v3 - v2);
    assertThat(r.bySize()).allMatch(Optimizer.Wallet::exact);
  }

  @Test
  void aHeldCardIsNeverCalledRedundant() {
    CardTerms good = card("good", 0, null, List.of(rule("rg", "food", "cashback", 900, 0, null)), null);
    CardTerms weak = card("weak", 0, null, List.of(rule("rw", "food", "cashback", 100, 0, null)), null);
    SpendVector s = spend("food", 1_000_000L);

    assertThat(Optimizer.optimize(List.of(good, weak), s, Set.of(), 1).dropped()).contains("weak");
    Optimizer.Result heldRun = Optimizer.optimize(List.of(good, weak), s, Set.of("weak"), 2);
    assertThat(heldRun.dropped()).as("the account owns it, so keeping it stays on the table").isEmpty();
    assertThat(heldRun.bySize().get(1).seats())
        .anyMatch(x -> x.cardProductId().equals("weak") && x.held());
  }

  @Test
  void tooManyContestedRootsFallBackToGreedyAndSaySo() {
    Map<String, Long> many = new LinkedHashMap<>();
    for (String root : List.of("a", "b", "c", "d", "e", "f", "g", "h", "i")) {
      many.put(root, 500_000L);
    }
    CardTerms p1 = card("p1", 0, null, List.of(rule("r1", null, "cashback", 500, 0, 50_000L)), null);
    CardTerms p2 = card("p2", 0, null, List.of(rule("r2", null, "cashback", 500, 0, 50_000L)), null);

    Optimizer.Result r = Optimizer.optimize(List.of(p1, p2), SpendVector.of(many), Set.of(), 2);
    assertThat(r.bySize().get(0).exact()).as("one card has nothing to contest").isTrue();
    assertThat(r.bySize().get(1).exact()).as("nine contested roots is past the exact bound").isFalse();
    assertThat(r.bySize().get(1).annualValuePaise()).as("and the greedy answer still finds both caps")
        .isEqualTo(1_200_000L);
    assertThat(r.dropped()).as("redundancy is not reported past MAX_ROOTS_FOR_PRUNING either").isEmpty();
  }

  @Test
  void theSameInputsGiveTheSameAnswerWhateverTheOrder() {
    CardTerms a = card("a", 0, null, List.of(rule("ra", "food", "cashback", 500, 0, null)), null);
    CardTerms b = card("b", 0, null, List.of(rule("rb", "food", "cashback", 500, 0, null)), null);
    CardTerms c = card("c", 0, null, List.of(rule("rc", "travel", "cashback", 500, 0, null)), null);
    SpendVector s = spend("food", 1_000_000L, "travel", 1_000_000L);
    Optimizer.Result r1 = Optimizer.optimize(List.of(a, b, c), s, Set.of(), 3);
    Optimizer.Result r2 = Optimizer.optimize(List.of(c, b, a), s, Set.of(), 3);
    assertThat(r1.best().cardProductIds()).isEqualTo(r2.best().cardProductIds());
    assertThat(r1.best().annualValuePaise()).isEqualTo(r2.best().annualValuePaise());
  }

  @Test
  void theEmptyCases() {
    SpendVector s = spend("food", 1_000_000L);
    CardTerms a = card("a", 0, null, List.of(rule("ra", "food", "cashback", 500, 0, null)), null);
    assertThat(Optimizer.optimize(List.of(), s, Set.of(), 3).bySize()).isEmpty();
    assertThat(Optimizer.optimize(List.of(a), SpendVector.empty(), Set.of(), 3).bySize()).isEmpty();
    assertThat(Optimizer.optimize(List.of(a), s, Set.of(), 3).bySize()).hasSize(1);
    assertThatThrownBy(() -> Optimizer.optimize(List.of(a), s, Set.of(), 4))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
