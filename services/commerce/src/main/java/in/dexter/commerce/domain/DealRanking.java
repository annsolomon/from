package in.dexter.commerce.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The marketplace order (MASTER_PLAN P17): {@code score = 0.8·relevance + 0.2·sponsor}, {@code relevance =
 * 0.6·cardMatch + 0.4·savingsNorm}. Card-match is a ranking branch, never a gate: a deal for a card the viewer does
 * not hold is still listed, lower. Integer basis points throughout (no floating point near money or ranks):
 * {@code score = (48·cardMatch + 32·savingsNorm + 20·sponsor) / 100}, each term 0..10000.
 *
 * <p>cardMatch: 10000 when the viewer holds one of the deal's eligible cards, 5000 when the deal is open to any card,
 * 0 otherwise (anonymous viewers hold nothing). savingsNorm: the deal's saving at its reference spend over the
 * largest such saving in the candidate set. sponsor: {@code sponsor_weight · 100} for sponsored deals.
 *
 * <p>Order: score DESC, priority DESC, id ASC (the id's canonical text, i.e. unsigned byte order) — total, so the
 * keyset cursor over it is exact.
 */
public final class DealRanking {
  public static final int FULL = 10_000;

  public enum CardMatch {
    HELD(FULL), OPEN(FULL / 2), OTHER(0);

    private final int bps;

    CardMatch(int bps) {
      this.bps = bps;
    }

    public int bps() {
      return bps;
    }
  }

  public record Candidate(UUID id, int priority, long savingsPaise, CardMatch match, boolean sponsored,
      int sponsorWeight) {}

  public record Ranked(Candidate candidate, int scoreBps) {
    public UUID id() {
      return candidate.id();
    }
  }

  /** Where the previous page ended. */
  public record Position(int scoreBps, int priority, UUID id) {}

  public static final Comparator<Ranked> ORDER = Comparator
      .comparingInt((Ranked r) -> -r.scoreBps())
      .thenComparingInt(r -> -r.candidate().priority())
      .thenComparing(r -> r.id().toString());

  private DealRanking() {}

  public static CardMatch match(Collection<UUID> eligible, Set<UUID> held) {
    if (eligible.isEmpty()) {
      return CardMatch.OPEN;
    }
    for (UUID e : eligible) {
      if (held.contains(e)) {
        return CardMatch.HELD;
      }
    }
    return CardMatch.OTHER;
  }

  public static int score(CardMatch match, int savingsNormBps, boolean sponsored, int sponsorWeight) {
    if (savingsNormBps < 0 || savingsNormBps > FULL) {
      throw new IllegalArgumentException("savingsNormBps must be 0.." + FULL);
    }
    if (sponsorWeight < 0 || sponsorWeight > 100) {
      throw new IllegalArgumentException("sponsorWeight must be 0..100");
    }
    int sponsor = sponsored ? sponsorWeight * 100 : 0;
    return (48 * match.bps() + 32 * savingsNormBps + 20 * sponsor) / 100;
  }

  public static List<Ranked> rank(List<Candidate> candidates) {
    long max = 0;
    for (Candidate c : candidates) {
      max = Math.max(max, c.savingsPaise());
    }
    List<Ranked> out = new ArrayList<>(candidates.size());
    for (Candidate c : candidates) {
      int norm = max == 0 ? 0 : (int) (Math.multiplyExact(c.savingsPaise(), (long) FULL) / max);
      out.add(new Ranked(c, score(c.match(), norm, c.sponsored(), c.sponsorWeight())));
    }
    out.sort(ORDER);
    return out;
  }

  /** The page after {@code after} (null = the first page) of an already ranked list. */
  public static List<Ranked> page(List<Ranked> ranked, Position after, int limit) {
    int from = 0;
    if (after != null) {
      Ranked probe = new Ranked(new Candidate(after.id(), after.priority(), 0, CardMatch.OTHER, false, 0),
          after.scoreBps());
      while (from < ranked.size() && ORDER.compare(ranked.get(from), probe) <= 0) {
        from++;
      }
    }
    return ranked.subList(from, Math.min(ranked.size(), from + limit));
  }

  public static Position positionOf(Ranked r) {
    return new Position(r.scoreBps(), r.candidate().priority(), r.id());
  }
}
