package in.dexter.reco.app;

import in.dexter.core.error.DexterException;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import in.dexter.reco.domain.BestCard;
import in.dexter.reco.domain.CardTerms;
import in.dexter.reco.domain.Offers;
import in.dexter.reco.domain.RecoErrors;
import in.dexter.reco.domain.Savings;
import in.dexter.reco.domain.SpendVector;
import in.dexter.reco.domain.Windows;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code GET /v1/recommendations/best-card} (MASTER_PLAN P15): which card the account should put this purchase on.
 *
 * <p>Live, never cached, and the answer depends on the amount — so there is nothing to cache. It also needs no
 * fan-out: everything it reads is already projected into reco (held cards, catalog terms and rules, this month's
 * per-card spend, active offers), which is what the plan means by "no sync fan-out". The old flow asked portfolio
 * and catalog at request time; this one asks its own tables.
 */
public final class BestCardService {
  private final TenantTx tx;
  private final RecoStore store;
  private final ObjectMapper mapper;
  private final Clock clock;

  public BestCardService(TenantTx tx, RecoStore store, ObjectMapper mapper, Clock clock) {
    this.tx = tx;
    this.store = store;
    this.mapper = mapper;
    this.clock = clock;
  }

  /** One offer as the API reports it. */
  public record OfferView(String offerId, String title, String kind, long valuePaise, boolean stackable) {}

  /** One held card's answer. */
  public record CardView(String userCardId, String cardProductId, String slug, String name, String ruleId,
      int rateBps, long rewardPaise, long offerPaise, long milestonePaise, long totalPaise, Long headroomPaise,
      boolean capExhausted, long periodSpendPaise, List<OfferView> offers, List<String> reasons) {}

  /** The whole answer, best first. */
  public record View(long amountPaise, String merchantId, String categoryPath, String period, String bestUserCardId,
      List<CardView> cards) {}

  /**
   * @param categoryPath the purchase's exact ltree path. Required when no merchant is given, because without
   *     either there is nothing to match a rule or an offer against
   */
  public View bestCard(Tenant tenant, UUID merchantId, String categoryPath, long amountPaise) {
    if (amountPaise <= 0) {
      throw new DexterException(RecoErrors.AMOUNT_REQUIRED, "amountPaise must be positive");
    }
    if (merchantId == null && (categoryPath == null || categoryPath.isBlank())) {
      throw new DexterException(RecoErrors.MERCHANT_OR_CATEGORY_REQUIRED,
          "Give merchantId or category (or both)");
    }
    Instant now = clock.instant();
    LocalDate month = Windows.month(LocalDate.ofInstant(now, RecommendationService.ZONE));
    String path = categoryPath == null || categoryPath.isBlank() ? "any" : categoryPath;

    return tx.read(tenant, () -> {
      UUID account = tenant.accountId();
      List<RecoStore.HeldCard> heldCards = store.heldCards(account);
      if (heldCards.isEmpty()) {
        return new View(amountPaise, str(merchantId), path, month.toString(), null, List.of());
      }
      List<UUID> productIds = heldCards.stream().map(RecoStore.HeldCard::cardProductId).distinct().toList();

      // the terms of just the held products, and their rules
      Map<UUID, CardTerms> termsById = new LinkedHashMap<>();
      Map<UUID, List<RecoStore.RuleRow>> rulesByCard = new LinkedHashMap<>();
      for (RecoStore.RuleRow r : store.rulesOf(productIds)) {
        rulesByCard.computeIfAbsent(r.cardProductId(), k -> new ArrayList<>()).add(r);
      }
      for (RecoStore.CardRow c : store.publishedCards()) {
        if (productIds.contains(c.cardProductId())) {
          termsById.put(c.cardProductId(),
              CardTermsMapper.of(c, rulesByCard.getOrDefault(c.cardProductId(), List.of()), mapper));
        }
      }

      List<Offers.Offer> offers = new ArrayList<>();
      for (RecoStore.OfferRow o : store.activeOffers(productIds)) {
        offers.add(new Offers.Offer(str(o.offerId()), str(o.cardProductId()), str(o.merchantId()),
            o.categoryPath(), o.title(), o.kind(), o.valueBps(), o.valuePaise(), o.maxDiscountPaise(),
            o.minSpendPaise(), o.validFrom(), o.validTo(), o.stackable()));
      }

      List<BestCard.Held> held = new ArrayList<>(heldCards.size());
      Map<String, Long> periodSpendByCard = new LinkedHashMap<>();
      for (RecoStore.HeldCard h : heldCards) {
        CardTerms terms = termsById.get(h.cardProductId());
        if (terms == null) {
          continue;      // held, but reco has no published terms for it - there is nothing to compute from
        }
        // this month's spend on this card, by root, which is what the cap headroom is measured against (A-86)
        Map<String, Long> byRoot = new LinkedHashMap<>();
        for (RecoStore.MonthRow m : store.cardMonth(account, h.userCardId(), month)) {
          byRoot.put(m.categoryRoot(), m.totalPaise());
        }
        SpendVector thisMonth = SpendVector.of(byRoot);
        long spent = thisMonth.monthlyTotal();
        periodSpendByCard.put(h.userCardId().toString(), spent);
        held.add(new BestCard.Held(h.userCardId().toString(), h.cardProductId().toString(), terms, spent,
            Savings.monthlyEarnedByRule(terms, thisMonth)));
      }

      BestCard.Answer answer = BestCard.rank(held, offers, str(merchantId), path, amountPaise, now);
      List<CardView> cards = new ArrayList<>(answer.picks().size());
      for (BestCard.Pick p : answer.picks()) {
        List<OfferView> os = p.offers().stream()
            .map(o -> new OfferView(o.offerId(), o.title(), o.kind(), o.valueOn(amountPaise), o.stackable()))
            .toList();
        cards.add(new CardView(p.userCardId(), p.cardProductId(), p.slug(), p.name(), p.ruleId(), p.rateBps(),
            p.rewardPaise(), p.offerPaise(), p.milestonePaise(), p.totalPaise(), p.headroomPaise(),
            p.capExhausted(), periodSpendByCard.getOrDefault(p.userCardId(), 0L), os, p.reasons()));
      }
      BestCard.Pick best = answer.best();
      return new View(amountPaise, str(merchantId), path, month.toString(),
          best == null ? null : best.userCardId(), cards);
    });
  }

  private static String str(UUID id) {
    return id == null ? null : id.toString();
  }
}
