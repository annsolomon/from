package in.dexter.reco.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What a catalog offer is worth on one transaction (MASTER_PLAN P15, best-card-now). Projected from
 * {@code catalog.offer.changed}, so reco never asks catalog at request time.
 *
 * <p>An offer is not a reward rule: it is attached to a card and optionally to a merchant or a category, it runs
 * between two instants, it has a minimum spend, and a percentage offer usually has a ceiling. Reco applies at most
 * one non-stackable offer plus every stackable one, which is the convention the catalog's own {@code stackable}
 * flag exists for.
 */
public final class Offers {
  private Offers() {}

  /**
   * One projected offer.
   *
   * @param kind {@code pct} (a share of the amount, bounded by {@code maxDiscountPaise}), {@code flat} or
   *     {@code cashback} (both a fixed {@code valuePaise})
   */
  public record Offer(String offerId, String cardProductId, String merchantId, String categoryPath, String title,
      String kind, Integer valueBps, Long valuePaise, Long maxDiscountPaise, long minSpendPaise, Instant validFrom,
      Instant validTo, boolean stackable) {

    /** Whether this offer applies to a purchase of {@code amountPaise} here, at {@code when}. */
    public boolean appliesTo(String merchantId, String categoryPath, long amountPaise, Instant when) {
      if (amountPaise < minSpendPaise) {
        return false;
      }
      if (validFrom != null && when.isBefore(validFrom)) {
        return false;
      }
      if (validTo != null && !when.isBefore(validTo)) {
        return false;
      }
      if (this.merchantId != null && !this.merchantId.equals(merchantId)) {
        return false;
      }
      // a category offer covers the path it names and everything under it, the way an ltree ancestor does
      return this.categoryPath == null || covers(this.categoryPath, categoryPath);
    }

    /** What this offer takes off {@code amountPaise}, in paise. */
    public long valueOn(long amountPaise) {
      if ("pct".equals(kind)) {
        long raw = valueBps == null ? 0 : amountPaise * valueBps / 10_000L;
        return maxDiscountPaise == null ? raw : Math.min(raw, maxDiscountPaise);
      }
      long flat = valuePaise == null ? 0 : valuePaise;
      // a flat offer never returns more than the purchase itself
      return Math.min(flat, amountPaise);
    }
  }

  /** Whether {@code ancestor} is the path itself or one of its ancestors ({@code food} covers {@code food.dining}). */
  public static boolean covers(String ancestor, String path) {
    if (ancestor == null || path == null) {
      return ancestor == null;
    }
    return path.equals(ancestor) || path.startsWith(ancestor + ".");
  }

  /** What one card's offers are worth on this purchase: the best non-stackable, plus every stackable one. */
  public record Applied(long totalPaise, List<Offer> offers) {
    public Applied {
      offers = offers == null ? List.of() : List.copyOf(offers);
    }
  }

  /**
   * The offers that apply, and what they are worth together. At most one non-stackable offer is taken — the most
   * valuable — because that is what "not stackable" means; every stackable offer is added on top.
   */
  public static Applied bestFor(List<Offer> offers, String cardProductId, String merchantId, String categoryPath,
      long amountPaise, Instant when) {
    List<Offer> applicable = new ArrayList<>();
    for (Offer o : offers) {
      if (o.cardProductId() != null && o.cardProductId().equals(cardProductId)
          && o.appliesTo(merchantId, categoryPath, amountPaise, when)) {
        applicable.add(o);
      }
    }
    // the best non-stackable first, then the stackables, so the chosen set is deterministic
    applicable.sort(Comparator.<Offer, Long>comparing(o -> o.valueOn(amountPaise)).reversed()
        .thenComparing(Offer::offerId));

    List<Offer> taken = new ArrayList<>();
    long total = 0;
    boolean tookExclusive = false;
    for (Offer o : applicable) {
      if (o.stackable()) {
        taken.add(o);
        total += o.valueOn(amountPaise);
      } else if (!tookExclusive) {
        tookExclusive = true;
        taken.add(o);
        total += o.valueOn(amountPaise);
      }
    }
    // an offer cannot hand back more than the purchase
    total = Math.min(total, amountPaise);
    taken.sort(Comparator.comparing(Offer::offerId));
    return new Applied(total, taken);
  }
}
