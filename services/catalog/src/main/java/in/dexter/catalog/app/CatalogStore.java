package in.dexter.catalog.app;

import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Public reads on the app pool (RLS: published cards, their current version and rules, active offers). */
public interface CatalogStore {
  /** A published card: its current version and that version's snapshot (JSON text). */
  record PublishedCard(UUID id, String slug, String name, int version, String snapshotJson) {}

  record SearchHit(UUID id, String slug, String name, String network, String tier, long annualFeePaise,
      double similarity) {}

  /** The rule that applies to a category on one card (the most specific matching rule). */
  record Potential(UUID cardId, String slug, String name, String rewardType, int rateBps, Long capPaise,
      String capPeriod, String rulePath) {}

  record Offer(UUID id, UUID issuerId, UUID cardProductId, UUID merchantId, String categoryPath, String title,
      String kind, Integer valueBps, Long valuePaise, Long maxDiscountPaise, long minSpendPaise, Instant validFrom,
      Instant validTo, boolean stackable, String termsUrl, int version) {}

  Page<PublishedCard> list(PageRequest page, CardFilter filter);

  Optional<PublishedCard> bySlug(String slug);

  List<SearchHit> search(String q, int limit);

  List<Potential> potential(String categoryPath, int limit);

  List<Offer> activeOffers(UUID merchantId, String categoryPath, Instant at, int limit);
}
