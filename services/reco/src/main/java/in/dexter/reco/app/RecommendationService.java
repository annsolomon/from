package in.dexter.reco.app;

import in.dexter.contracts.events.reco.RecommendationUpdatedV1;
import in.dexter.core.error.DexterException;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.OutboxWriter;
import in.dexter.reco.domain.CardTerms;
import in.dexter.reco.domain.RecoErrors;
import in.dexter.reco.domain.Savings;
import in.dexter.reco.domain.Scorer;
import in.dexter.reco.domain.SpendVector;
import in.dexter.reco.domain.StateHash;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * The recommendation an account is served, and the recompute behind it (MASTER_PLAN P14).
 *
 * <p>Reads are cached in {@code recommendations}, keyed by a {@code state_hash} of the inputs. A read computes the
 * current hash — three small per-account queries — and serves the stored result when it matches. When it does not,
 * the result is recomputed on the spot for that one account and served fresh (A-83): the alternative, fanning a
 * dirty flag out to every account whenever a card is republished, would enqueue a recompute for the whole user base
 * for a change most of them cannot see.
 *
 * <p>{@link #recompute} is also what the sweeper calls, and it is the only writer: it stores a result and publishes
 * {@code reco.recommendation.updated} <em>only</em> when the hash moved, so 50 expense events in a burst coalesce
 * into one recompute and at most one event.
 */
public final class RecommendationService {
  public static final String PATH = "/v1/recommendations";
  /** Spend days, windows and "computed at" are the account's local day. */
  public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
  /** The window the scorer uses; the other two are kept for the explanation and for P15. */
  public static final String SCORING_WINDOW = "3m";
  /** At most this many cards are scored in one pass, so a catalog of any size cannot blow a request up. */
  public static final int MAX_CANDIDATES = 500;

  /** A recompute's outcome: whether anything changed, which is what decides the event. */
  public record Recomputed(UUID accountId, String stateHash, boolean changed, Instant computedAt, View view) {}

  /** What the API returns. */
  public record View(String window, long monthlySpendPaise, Map<String, Long> spendByRoot, int savingsWeightBps,
      int thresholdBps, Instant computedAt, boolean fresh, List<Card> recommended, List<Card> notRecommended) {}

  /** One card in the ranking. */
  public record Card(UUID cardProductId, String slug, String name, String issuerCode, boolean held,
      long annualSavingsPaise, int savingsBps, int fitBps, int scoreBps, List<String> reasons) {}

  /** The full breakdown for one card: the arithmetic, not a number. */
  public record Explanation(UUID cardProductId, String slug, String name, String issuerCode, boolean held,
      String window, long monthlySpendPaise, long annualRewardPaise, long annualSavingsPaise, int savingsBps,
      int fitBps, int scoreBps, boolean recommended, Fee fee, Milestone milestone, List<Line> lines,
      Map<String, Integer> rewardRateBpsByRoot, List<String> reasons, Instant computedAt) {}

  public record Fee(long annualFeePaise, Long waiverSpendPaise, int waiverProbabilityBps, long expectedFeePaise) {}

  public record Milestone(long annualValuePaise, boolean reached, long monthlyThresholdPaise,
      long monthlyShortfallPaise) {}

  /** @param shareBps how much of the root's spend the matched rule was assumed to see (Rules.share, A-81) */
  public record Line(String categoryRoot, String ruleId, String rewardType, int rateBps, int shareBps,
      long monthlySpendPaise, long annualSpendConsideredPaise, long annualRewardPaise, boolean capBound,
      Long annualCapPaise, boolean capNotConvertible) {}

  private final TenantTx tx;
  private final RecoStore store;
  private final OutboxWriter outbox;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final int savingsWeightBps;
  private final int thresholdBps;

  public RecommendationService(TenantTx tx, RecoStore store, OutboxWriter outbox, ObjectMapper mapper, Clock clock,
      int savingsWeightBps, int thresholdBps) {
    this.tx = tx;
    this.store = store;
    this.outbox = outbox;
    this.mapper = mapper;
    this.clock = clock;
    this.savingsWeightBps = savingsWeightBps;
    this.thresholdBps = thresholdBps;
  }

  // ---------------------------------------------------------------- reads

  /** The account's recommendation, recomputed first when the stored one was built from different inputs. */
  public View get(Tenant tenant) {
    return get(tenant, List.of());
  }

  /**
   * @param onlyProducts when non-empty, score just these products (the plan's {@code ?cardProductIds=}, for
   *     ranking a search result). A subset is never cached: it is a different question from "what should I get".
   */
  public View get(Tenant tenant, List<UUID> onlyProducts) {
    if (!onlyProducts.isEmpty()) {
      return tx.read(tenant, () -> {
        Inputs in = inputs(tenant.accountId());
        List<CardTerms> subset = new ArrayList<>();
        Set<UUID> wanted = new LinkedHashSet<>(onlyProducts);
        for (CardTerms c : in.candidates()) {
          if (wanted.contains(UUID.fromString(c.cardProductId()))) {
            subset.add(c);
          }
        }
        Scorer.Ranking ranking = Scorer.rank(subset, in.spend(), in.heldProductIds(), savingsWeightBps,
            thresholdBps);
        return view(in, ranking, now(), true);
      });
    }
    Recomputed r = recompute(tenant.accountId());
    return r.view();
  }

  /** One card's breakdown. 404 when the product is not in the catalog projection. */
  public Explanation explain(Tenant tenant, UUID cardProductId) {
    return tx.read(tenant, () -> {
      Inputs in = inputs(tenant.accountId());
      CardTerms card = in.candidates().stream()
          .filter(c -> c.cardProductId().equals(cardProductId.toString())).findFirst()
          .orElseThrow(() -> new DexterException(RecoErrors.CARD_NOT_IN_CATALOG,
              "No published card " + cardProductId));
      Scorer.Ranking ranking = Scorer.rank(in.candidates(), in.spend(), in.heldProductIds(), savingsWeightBps,
          thresholdBps);
      Scorer.Scored scored = ranking.recommended().stream()
          .filter(s -> s.cardProductId().equals(cardProductId.toString())).findFirst()
          .orElseGet(() -> ranking.notRecommended().stream()
              .filter(s -> s.cardProductId().equals(cardProductId.toString())).findFirst().orElseThrow());
      boolean recommended = ranking.recommended().stream()
          .anyMatch(s -> s.cardProductId().equals(cardProductId.toString()));
      Savings.Result sv = scored.savings();
      List<Line> lines = new ArrayList<>(sv.lines().size());
      for (Savings.Line l : sv.lines()) {
        lines.add(new Line(l.categoryRoot(), l.ruleId(), l.rewardType(), l.rateBps(), l.shareBps(),
            l.monthlySpendPaise(), l.annualSpendConsideredPaise(), l.annualRewardPaise(), l.capBound(),
            l.annualCapPaise(), l.capUnconvertible()));
      }
      return new Explanation(cardProductId, card.slug(), card.name(), card.issuerCode(), scored.held(),
          SCORING_WINDOW, in.spend().monthlyTotal(), sv.annualRewardPaise(), sv.annualSavingsPaise(),
          scored.savingsBps(), scored.fitBps(), scored.scoreBps(), recommended,
          new Fee(sv.fee().annualFeePaise(), sv.fee().waiverSpendPaise(), sv.fee().waiverProbabilityBps(),
              sv.fee().expectedFeePaise()),
          new Milestone(sv.milestone().annualValuePaise(), sv.milestone().reached(),
              sv.milestone().monthlyThresholdPaise(), sv.milestone().monthlyShortfallPaise()),
          lines, scored.rewardVector(), scored.reasons(), now());
    });
  }

  // ---------------------------------------------------------------- the recompute

  /**
   * Recomputes one account, stores the result when it differs from the stored one, publishes the event when it
   * does, and clears {@code dirty} either way. Safe to call concurrently with itself: the write is an upsert keyed
   * by the account and the event is only published on a change.
   */
  public Recomputed recompute(UUID account) {
    return tx.write(Tenant.user(account), () -> {
      Inputs in = inputs(account);
      Map<String, Integer> versions = new LinkedHashMap<>();
      for (CardTerms c : in.candidates()) {
        versions.put(c.cardProductId(), in.versions().get(c.cardProductId()));
      }
      String hash = StateHash.of(in.spend(), in.heldProductIds(), versions, savingsWeightBps, thresholdBps);
      Optional<RecoStore.ResultRow> stored = store.result(account);
      Instant now = now();

      if (stored.isPresent() && stored.get().stateHash().equals(hash)) {
        // nothing the engine reads has changed: clear dirty, keep the result, publish nothing
        store.markComputed(account, now);
        return new Recomputed(account, hash, false, stored.get().computedAt(),
            parse(stored.get().payloadJson(), stored.get().computedAt()));
      }

      Scorer.Ranking ranking = Scorer.rank(in.candidates(), in.spend(), in.heldProductIds(), savingsWeightBps,
          thresholdBps);
      View view = view(in, ranking, now, true);
      store.putResult(new RecoStore.ResultRow(account, hash, now, mapper.writeValueAsString(view)));
      store.markComputed(account, now);
      publish(account, hash, ranking, now);
      return new Recomputed(account, hash, true, now, view);
    });
  }

  private void publish(UUID account, String hash, Scorer.Ranking ranking, Instant now) {
    Scorer.Scored top = ranking.recommended().isEmpty() ? null : ranking.recommended().get(0);
    outbox.append(RecoEvents.RECOMMENDATION_UPDATED, account, account, 0,
        new RecommendationUpdatedV1(account, hash, top == null ? null : top.cardProductId(),
            top == null ? null : top.slug(), top == null ? 0 : top.annualSavingsPaise(),
            top == null ? 0 : top.scoreBps(), ranking.recommended().size(), ranking.notRecommended().size(),
            now.toString()));
  }

  // ---------------------------------------------------------------- inputs

  /** Everything one scoring pass reads, gathered once so the hash and the ranking cannot disagree. */
  record Inputs(SpendVector spend, Set<String> heldProductIds, List<CardTerms> candidates,
      Map<String, Integer> versions) {}

  private Inputs inputs(UUID account) {
    Map<String, Long> byRoot = new LinkedHashMap<>();
    for (RecoStore.ProfileRow p : store.profile(account, SCORING_WINDOW)) {
      byRoot.put(p.categoryRoot(), p.monthlyAvgPaise());
    }
    Set<String> held = new LinkedHashSet<>();
    for (RecoStore.HeldCard h : store.heldCards(account)) {
      held.add(h.cardProductId().toString());
    }
    List<RecoStore.CardRow> cards = store.publishedCards();
    if (cards.size() > MAX_CANDIDATES) {
      cards = cards.subList(0, MAX_CANDIDATES);
    }
    List<UUID> ids = cards.stream().map(RecoStore.CardRow::cardProductId).toList();
    Map<UUID, List<RecoStore.RuleRow>> rulesByCard = new LinkedHashMap<>();
    for (RecoStore.RuleRow r : store.rulesOf(ids)) {
      rulesByCard.computeIfAbsent(r.cardProductId(), k -> new ArrayList<>()).add(r);
    }
    List<CardTerms> candidates = new ArrayList<>(cards.size());
    Map<String, Integer> versions = new LinkedHashMap<>();
    for (RecoStore.CardRow c : cards) {
      candidates.add(CardTermsMapper.of(c, rulesByCard.getOrDefault(c.cardProductId(), List.of()), mapper));
      versions.put(c.cardProductId().toString(), c.currentVersion());
    }
    return new Inputs(SpendVector.of(byRoot), held, candidates, versions);
  }

  private View view(Inputs in, Scorer.Ranking ranking, Instant computedAt, boolean fresh) {
    return new View(SCORING_WINDOW, in.spend().monthlyTotal(), in.spend().descending(), ranking.savingsWeightBps(),
        ranking.thresholdBps(), computedAt, fresh, cards(ranking.recommended()), cards(ranking.notRecommended()));
  }

  private List<Card> cards(List<Scorer.Scored> scored) {
    List<Card> out = new ArrayList<>(scored.size());
    for (Scorer.Scored s : scored) {
      out.add(new Card(UUID.fromString(s.cardProductId()), s.slug(), s.name(), s.issuerCode(), s.held(),
          s.annualSavingsPaise(), s.savingsBps(), s.fitBps(), s.scoreBps(), s.reasons()));
    }
    return out;
  }

  private View parse(String json, Instant computedAt) {
    View v = mapper.readValue(json, View.class);
    // the stored payload was fresh when written; it is still the answer, so `fresh` stays true
    return new View(v.window(), v.monthlySpendPaise(), v.spendByRoot(), v.savingsWeightBps(), v.thresholdBps(),
        computedAt, true, v.recommended(), v.notRecommended());
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  /** The account's local day, for the windows. */
  public LocalDate today() {
    return LocalDate.ofInstant(clock.instant(), ZONE);
  }
}
