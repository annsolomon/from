package in.dexter.reco.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The wallet optimizer (MASTER_PLAN P15): which two or three cards, used together, are worth the most to this
 * account — and what the second and third card actually add.
 *
 * <p>Pure and deterministic, like the rest of {@code reco.domain}. The value of a wallet is <em>not</em> the sum of
 * its cards' individual scores, for three reasons the plan names and this class handles in one place:
 *
 * <ol>
 *   <li><b>Exclusive assignment.</b> A rupee is spent once. Each category root is routed to exactly one card in
 *       the wallet, so two cards that both earn 5 % on food do not both earn it.</li>
 *   <li><b>Caps are per card per period.</b> A card's cap is consumed by whatever is routed to it, so a second
 *       card is often worth more than its solo score suggests — it picks up what the first card's cap could not
 *       hold.</li>
 *   <li><b>Fees and milestones follow the routed spend.</b> A three-card wallet splits the spend three ways, so
 *       each card is less likely to clear its own fee-waiver threshold or reach its milestone. Charging every card
 *       its solo waiver probability would make big wallets look free.</li>
 * </ol>
 *
 * <p>All three fall out of valuing a card with {@link Savings#of} against {@link SpendVector#only} the roots routed
 * to it — the same arithmetic {@code GET /v1/recommendations} uses, not a second copy of it (the A-82 rule).
 *
 * <h2>The search</h2>
 *
 * <p>{@link #prune} drops a card that another beats on every root while costing no more and reaching no less of a
 * milestone; {@link #beam} keeps the {@link #BEAM} best of what remains by solo value, always keeping the cards the
 * account already holds so the answer can say "keep what you have". Every wallet of 1, 2 and 3 cards over that
 * beam is then valued exhaustively — 15 cards is 575 wallets, small enough to be exact rather than clever.
 *
 * <p>Valuing one wallet is the part that is not obvious. Routing roots to cards with per-card caps is a
 * generalized assignment problem, so {@link #assign} splits it: a root more than one card could earn on is
 * <em>contested</em> and those are brute-forced together, while a root with one possible home is assigned with no
 * search. Roots cannot be decided one at a time, because which root a card's cap is spent on changes what the
 * whole wallet is worth. In practice almost every root is uncontested. {@link #MAX_CONTESTED} bounds the brute
 * force; past it the routing is greedy and the wallet says {@code exact: false}, because an approximate number
 * that admits it beats a wrong one that does not.
 *
 * <p>Every card-against-a-set-of-roots valuation is memoized per call ({@link Ctx}), which is what keeps the
 * nesting affordable: the distinct valuations are a few hundred per wallet, not the hundreds of thousands the
 * routings would otherwise ask for.
 */
public final class Optimizer {
  /** How many cards survive to the exhaustive stage (MASTER_PLAN P15: beam top-15). */
  public static final int BEAM = 15;
  /** The plan's wallet sizes: one, two and three cards. */
  public static final int MAX_WALLET = 3;
  /**
   * How many contested roots are routed exactly: 3^6 = 729 routings per wallet, each a handful of memoized
   * lookups. Beyond it the routing is greedy and {@link Wallet#exact} is false.
   */
  public static final int MAX_CONTESTED = 6;
  /**
   * How many cards dominance pruning compares pairwise. Pruning is O(pool² × 2^roots), so it runs inside a pool of
   * the best cards by solo value rather than over a whole catalog — a card outside the best 32 cannot reach a
   * 15-card beam anyway.
   */
  public static final int POOL = 32;
  /** Dominance is decided over every subset of the spent roots, so it is only attempted up to this many. */
  public static final int MAX_ROOTS_FOR_PRUNING = 8;

  private Optimizer() {}

  /** One card in a wallet: what it was given to earn on, and what it is worth there. */
  public record Seat(String cardProductId, String slug, String name, boolean held, List<String> categoryRoots,
      long annualValuePaise, Savings.Result savings) {
    public Seat {
      categoryRoots = categoryRoots == null ? List.of() : List.copyOf(categoryRoots);
    }
  }

  /**
   * A valued wallet.
   *
   * @param marginalValuePaise what this wallet is worth over the best wallet one card smaller — the plan's
   *     "marginal value of adding each next card". Zero for a single card, and negative when a further card costs
   *     more in fee than it earns, which is worth saying out loud
   * @param exact false when the routing fell back to greedy because too many roots were contested
   */
  public record Wallet(int size, List<Seat> seats, long annualValuePaise, long annualRewardPaise,
      long annualFeePaise, long marginalValuePaise, boolean exact, Map<String, String> routing) {
    public Wallet {
      seats = seats == null ? List.of() : List.copyOf(seats);
      routing = routing == null ? Map.of() : Map.copyOf(routing);
    }

    public List<String> cardProductIds() {
      return seats.stream().map(Seat::cardProductId).toList();
    }
  }

  /**
   * The best wallet at each size from 1 upwards, smallest first.
   *
   * @param dropped the cards dominance pruning removed, for the explanation
   */
  public record Result(List<Wallet> bySize, int considered, int afterPruning, int inBeam, List<String> dropped) {
    public Result {
      bySize = bySize == null ? List.of() : List.copyOf(bySize);
      dropped = dropped == null ? List.of() : List.copyOf(dropped);
    }

    /** The wallet worth the most; empty only when there was nothing to rank. */
    public Wallet best() {
      Wallet best = null;
      for (Wallet w : bySize) {
        if (best == null || better(w, best)) {
          best = w;
        }
      }
      return best;
    }
  }

  public static Result optimize(List<CardTerms> candidates, SpendVector spend, Set<String> heldProductIds) {
    return optimize(candidates, spend, heldProductIds, MAX_WALLET);
  }

  /** Prune, beam, then every wallet up to {@code maxSize}, valued exactly. */
  public static Result optimize(List<CardTerms> candidates, SpendVector spend, Set<String> heldProductIds,
      int maxSize) {
    if (maxSize < 1 || maxSize > MAX_WALLET) {
      throw new IllegalArgumentException("a wallet is 1 to " + MAX_WALLET + " cards");
    }
    List<CardTerms> all = candidates == null ? List.of() : candidates;
    Set<String> held = heldProductIds == null ? Set.of() : heldProductIds;
    if (all.isEmpty() || spend == null || spend.isEmpty()) {
      return new Result(List.of(), all.size(), 0, 0, List.of());
    }
    Ctx ctx = new Ctx(spend);

    // ordered by solo value first, so both the dominance report and the beam see the cards that could matter
    List<CardTerms> ordered = bySoloValue(all, ctx, held);
    List<CardTerms> pool = ordered.subList(0, Math.min(POOL, ordered.size()));
    List<String> dominated = new ArrayList<>();
    redundant(pool, ctx, held, dominated);
    List<CardTerms> beam = beam(pool, ctx, held);

    List<Wallet> bySize = new ArrayList<>();
    long previousBest = 0;
    for (int size = 1; size <= Math.min(maxSize, beam.size()); size++) {
      Wallet best = null;
      for (List<CardTerms> combo : combinations(beam, size)) {
        Wallet w = value(combo, ctx, held);
        if (best == null || better(w, best)) {
          best = w;
        }
      }
      if (best == null) {
        break;
      }
      bySize.add(new Wallet(size, best.seats(), best.annualValuePaise(), best.annualRewardPaise(),
          best.annualFeePaise(), size == 1 ? 0 : best.annualValuePaise() - previousBest, best.exact(),
          best.routing()));
      previousBest = best.annualValuePaise();
    }
    return new Result(bySize, all.size(), pool.size() - dominated.size(), beam.size(), dominated);
  }

  /** Higher value wins; ties go to the cheaper wallet, then to the lexicographically first card list. */
  private static boolean better(Wallet a, Wallet b) {
    if (a.annualValuePaise() != b.annualValuePaise()) {
      return a.annualValuePaise() > b.annualValuePaise();
    }
    if (a.annualFeePaise() != b.annualFeePaise()) {
      return a.annualFeePaise() < b.annualFeePaise();
    }
    return String.join(",", a.cardProductIds()).compareTo(String.join(",", b.cardProductIds())) < 0;
  }

  // ------------------------------------------------------------------------------------- memo for one call

  /**
   * One optimize() call's memo. Both lookups are pure functions of the card and the roots given to it, so they are
   * cached for the call and thrown away with it — never static, which would leak one account's figures into
   * another's and make the engine's determinism a matter of luck.
   */
  static final class Ctx {
    final SpendVector spend;
    final List<String> roots;
    private final Map<String, Savings.Result> bySet = new HashMap<>();

    Ctx(SpendVector spend) {
      this.spend = spend;
      this.roots = List.copyOf(spend.descending().keySet());
    }

    /** What this card is worth given exactly these roots, fee and milestone included. */
    Savings.Result value(CardTerms card, Set<String> assignedRoots) {
      String key = card.cardProductId() + "|" + String.join(",", new TreeSet<>(assignedRoots));
      return bySet.computeIfAbsent(key, k -> Savings.of(card, spend.only(assignedRoots)));
    }

    /** What this card earns on one root with nothing competing — the pruning and routing comparison basis. */
    long rootReward(CardTerms card, String root) {
      return value(card, Set.of(root)).annualRewardPaise();
    }
  }

  // ------------------------------------------------------------------------------------------------- pruning

  /**
   * Which cards another card makes redundant. This is <b>reported, not acted on</b> — {@link Result#dropped} is
   * an explanation ("you would not hold both of these"), and the search still considers every card in the pool.
   *
   * <p>That is a deliberate retreat from the plan's "dominance pruning", and the reason is worth recording. Every
   * attempt to make pairwise dominance <em>safe</em> failed against a brute force, because every quantity that
   * decides a wallet's value is per card and none of them are additive:
   *
   * <ul>
   *   <li>a <b>cap</b> is per card, so a second copy of a capped card brings a second cap — the first version
   *       pruned exactly the card that makes a two-card wallet worth having;</li>
   *   <li>a <b>fee waiver</b> depends on how much spend the card is given, so a Rs 1,000 fee that gets waived
   *       beats a Rs 500 fee that does not, and which happens depends on the routing — comparing sticker fees
   *       dropped genuinely better cards;</li>
   *   <li>a <b>milestone</b> is per card, so two cards each reaching their own beats one card reaching one, and a
   *       card that brings a milestone is never redundant however well another card earns.</li>
   * </ul>
   *
   * <p>Each fix exposed the next, and a condition strong enough to be sound would have had to evaluate the card in
   * every wallet it might join — which is the search itself. So the search is exhaustive over the beam, and the
   * beam is the one approximation, which is what MASTER_PLAN names anyway. {@code OptimizerTest} compares the
   * answer against a brute force over the whole candidate set, at every size, and that comparison is only
   * meaningful because nothing is pruned out from under it.
   */
  static void redundant(List<CardTerms> cards, Ctx ctx, Set<String> held, List<String> dominated) {
    if (ctx.roots.size() > MAX_ROOTS_FOR_PRUNING) {
      return;                    // too many roots to say honestly; the report is left empty rather than guessed
    }
    List<Set<String>> subsets = subsetsOf(ctx.roots);
    for (CardTerms b : cards) {
      if (held.contains(b.cardProductId())) {
        continue;                // the account owns it, so "keep what you have" is never called redundant
      }
      for (CardTerms a : cards) {
        if (!a.cardProductId().equals(b.cardProductId()) && dominates(a, b, ctx, subsets)) {
          dominated.add(b.cardProductId());
          break;
        }
      }
    }
  }

  /**
   * Whether {@code a} makes {@code b} redundant: {@code a}'s value is at least {@code b}'s for <em>every</em> set
   * of roots {@code b} could be given, including the empty one.
   *
   * <p>Comparing fees and milestones as declared numbers is not enough and was the first version's bug. A Rs 1,000
   * fee that the account's spend waives costs nothing, while a Rs 500 fee with no waiver costs Rs 500 — so the
   * bigger sticker fee can be the cheaper card, and which it is depends on how much spend the card is given. The
   * empty set matters for the same reason: a card that ends up with nothing routed to it still pays its fee, and
   * that is the case where a cheap card beats an expensive one.
   *
   * <p>So the comparison is made where the arithmetic already lives — {@link Savings} — for all 2^roots subsets,
   * which is why {@link #MAX_ROOTS_FOR_PRUNING} bounds it.
   */
  private static boolean dominates(CardTerms a, CardTerms b, Ctx ctx, List<Set<String>> subsets) {
    // b's milestone is its own: two cards each reaching their own milestone can beat one card reaching one, so a
    // card that brings a milestone is never redundant however well another card earns. A brute force found this
    // too - it preferred three-card wallets the optimizer had pruned a milestone card out of.
    if (b.milestone() != null && b.milestone().monthlyBenefitPaise() > 0) {
      return false;
    }
    for (String root : ctx.roots) {
      CardTerms.Rule used = Rules.best(a.rules(), root);
      if (used != null && used.monthlyCapPaise() != null) {
        return false;
      }
      // per-root reward dominance, which with a uncapped makes a's reward additive and at least b's on any set
      if (ctx.rootReward(a, root) < ctx.rootReward(b, root)) {
        return false;
      }
    }
    boolean strictlyBetterSomewhere = false;
    for (Set<String> s : subsets) {
      long va = ctx.value(a, s).annualSavingsPaise();
      long vb = ctx.value(b, s).annualSavingsPaise();
      if (va < vb) {
        return false;
      }
      if (va > vb) {
        strictlyBetterSomewhere = true;
      }
    }
    // identical on every subset: keep whichever id sorts first, so a tie never drops both
    return strictlyBetterSomewhere || a.cardProductId().compareTo(b.cardProductId()) < 0;
  }

  /** Every subset of the roots, the empty one included. */
  static List<Set<String>> subsetsOf(List<String> roots) {
    List<Set<String>> out = new ArrayList<>(1 << roots.size());
    for (int mask = 0; mask < (1 << roots.size()); mask++) {
      Set<String> s = new LinkedHashSet<>();
      for (int i = 0; i < roots.size(); i++) {
        if ((mask & (1 << i)) != 0) {
          s.add(roots.get(i));
        }
      }
      out.add(s);
    }
    return out;
  }

  /** The candidates in descending solo value, held cards first among equals, then by id. */
  static List<CardTerms> bySoloValue(List<CardTerms> cards, Ctx ctx, Set<String> held) {
    Set<String> allRoots = Set.copyOf(ctx.roots);
    List<CardTerms> sorted = new ArrayList<>(cards);
    sorted.sort(Comparator.<CardTerms, Long>comparing(
            c -> ctx.value(c, allRoots).annualSavingsPaise()).reversed()
        .thenComparing((CardTerms c) -> held.contains(c.cardProductId()) ? 0 : 1)
        .thenComparing(CardTerms::cardProductId));
    return sorted;
  }

  // ------------------------------------------------------------------------------------------------- beam

  /** The {@link #BEAM} best remaining cards by solo value, with every held card kept regardless. */
  static List<CardTerms> beam(List<CardTerms> cards, Ctx ctx, Set<String> held) {
    Map<String, Long> solo = new LinkedHashMap<>();
    for (CardTerms c : cards) {
      solo.put(c.cardProductId(), ctx.value(c, Set.copyOf(ctx.roots)).annualSavingsPaise());
    }
    Comparator<CardTerms> byValue = Comparator.<CardTerms, Long>comparing(c -> solo.get(c.cardProductId()))
        .reversed().thenComparing(CardTerms::cardProductId);
    List<CardTerms> sorted = new ArrayList<>(cards);
    sorted.sort(byValue);

    List<CardTerms> out = new ArrayList<>();
    for (CardTerms c : sorted) {
      if (held.contains(c.cardProductId())) {
        out.add(c);
      }
    }
    for (CardTerms c : sorted) {
      if (out.size() >= BEAM) {
        break;
      }
      if (!held.contains(c.cardProductId())) {
        out.add(c);
      }
    }
    out.sort(byValue);   // held cards can themselves exceed the beam; keep them all, in a deterministic order
    return out;
  }

  // ------------------------------------------------------------------------------------------------- valuing

  /** Values one wallet: route every root to one card, then score each card on what it was given. */
  static Wallet value(List<CardTerms> wallet, Ctx ctx, Set<String> held) {
    Assignment a = assign(wallet, ctx);
    List<Seat> seats = new ArrayList<>(wallet.size());
    long reward = 0;
    long fees = 0;
    long total = 0;
    for (CardTerms c : wallet) {
      Set<String> mine = a.byCard().getOrDefault(c.cardProductId(), Set.of());
      Savings.Result r = ctx.value(c, mine);
      seats.add(new Seat(c.cardProductId(), c.slug(), c.name(), held.contains(c.cardProductId()),
          List.copyOf(mine), r.annualSavingsPaise(), r));
      reward += r.annualRewardPaise();
      fees += r.fee().expectedFeePaise();
      total += r.annualSavingsPaise();
    }
    Map<String, String> routing = new LinkedHashMap<>();
    for (String root : ctx.roots) {
      String card = a.byRoot().get(root);
      if (card != null) {
        routing.put(root, card);
      }
    }
    return new Wallet(wallet.size(), seats, total, reward, fees, 0, a.exact(), routing);
  }

  /** Which card each root was routed to. */
  record Assignment(Map<String, Set<String>> byCard, Map<String, String> byRoot, boolean exact) {}

  /**
   * Routes every root to one card in the wallet, maximizing the wallet's total value.
   *
   * <p>Every root is a candidate for every card, including cards that earn nothing on it. That is not a
   * formality: spend counts towards a card's fee waiver and its milestone whether or not it earns rewards, so
   * putting the groceries on a card that pays nothing on groceries can still be what waives its fee. An earlier
   * version routed a root only to cards that earned on it, and a brute force found better wallets than the
   * optimizer did — the search was fine, the model was wrong.
   */
  static Assignment assign(List<CardTerms> wallet, Ctx ctx) {
    Map<String, Set<String>> byCard = new LinkedHashMap<>();
    Map<String, String> byRoot = new LinkedHashMap<>();
    for (CardTerms c : wallet) {
      byCard.put(c.cardProductId(), new LinkedHashSet<>());
    }
    if (wallet.size() == 1) {
      // one card carries everything, which is also what the account would really do
      for (String root : ctx.roots) {
        place(byCard, byRoot, wallet.get(0).cardProductId(), root);
      }
      return new Assignment(byCard, byRoot, true);
    }

    List<String> contested = new ArrayList<>(ctx.roots);
    if (contested.isEmpty()) {
      return new Assignment(byCard, byRoot, true);
    }
    if (contested.size() > MAX_CONTESTED) {
      greedy(wallet, ctx, contested, byCard, byRoot);
      return new Assignment(byCard, byRoot, false);
    }

    int n = contested.size();
    int k = wallet.size();
    long best = Long.MIN_VALUE;
    int[] bestPick = new int[n];
    int[] pick = new int[n];
    int routings = (int) Math.round(Math.pow(k, n));
    for (int code = 0; code < routings; code++) {
      int rest = code;
      for (int i = 0; i < n; i++) {
        pick[i] = rest % k;
        rest /= k;
      }
      long v = valueOf(wallet, ctx, byCard, contested, pick);
      if (v > best) {
        best = v;
        bestPick = pick.clone();
      }
    }
    for (int i = 0; i < n; i++) {
      place(byCard, byRoot, wallet.get(bestPick[i]).cardProductId(), contested.get(i));
    }
    return new Assignment(byCard, byRoot, true);
  }

  /** The wallet's total value with the pre-assigned roots plus this routing of the contested ones. */
  private static long valueOf(List<CardTerms> wallet, Ctx ctx, Map<String, Set<String>> fixed,
      List<String> contested, int[] pick) {
    long total = 0;
    for (int c = 0; c < wallet.size(); c++) {
      CardTerms card = wallet.get(c);
      Set<String> mine = new LinkedHashSet<>(fixed.getOrDefault(card.cardProductId(), Set.of()));
      for (int i = 0; i < contested.size(); i++) {
        if (pick[i] == c) {
          mine.add(contested.get(i));
        }
      }
      total += ctx.value(card, mine).annualSavingsPaise();
    }
    return total;
  }

  /**
   * The fallback for an account with more contested roots than {@link #MAX_CONTESTED}: take them in descending
   * spend and give each to whichever card gains most by adding it. Order-dependent and not optimal, which is
   * exactly why the wallet it produces is marked {@code exact: false}.
   */
  private static void greedy(List<CardTerms> wallet, Ctx ctx, List<String> contested,
      Map<String, Set<String>> byCard, Map<String, String> byRoot) {
    for (String root : contested) {
      String bestCard = null;
      long bestGain = Long.MIN_VALUE;
      for (CardTerms c : wallet) {
        Set<String> mine = byCard.get(c.cardProductId());
        long before = ctx.value(c, mine).annualSavingsPaise();
        Set<String> with = new LinkedHashSet<>(mine);
        with.add(root);
        long gain = ctx.value(c, with).annualSavingsPaise() - before;
        if (gain > bestGain
            || (gain == bestGain && bestCard != null && c.cardProductId().compareTo(bestCard) < 0)) {
          bestGain = gain;
          bestCard = c.cardProductId();
        }
      }
      place(byCard, byRoot, bestCard, root);
    }
  }

  private static void place(Map<String, Set<String>> byCard, Map<String, String> byRoot, String card, String root) {
    byCard.get(card).add(root);
    byRoot.put(root, card);
  }

  // ------------------------------------------------------------------------------------------------- combos

  /** Every combination of {@code size} cards, in input order so the search is deterministic. */
  static List<List<CardTerms>> combinations(List<CardTerms> cards, int size) {
    List<List<CardTerms>> out = new ArrayList<>();
    combine(cards, size, 0, new ArrayList<>(), out);
    return out;
  }

  private static void combine(List<CardTerms> cards, int size, int from, List<CardTerms> acc,
      List<List<CardTerms>> out) {
    if (acc.size() == size) {
      out.add(List.copyOf(acc));
      return;
    }
    for (int i = from; i < cards.size(); i++) {
      acc.add(cards.get(i));
      combine(cards, size, i + 1, acc, out);
      acc.remove(acc.size() - 1);
    }
  }
}
