package in.dexter.contracts.events.catalog;

import java.util.UUID;

/**
 * {@code catalog.offer.changed} v1 (MASTER_PLAN P09) on {@code dexter.catalog.offer.v1} (key = offer id): an offer
 * was created or withdrawn (status {@code active} / {@code withdrawn}); the payload is the whole offer.
 */
public record OfferChangedV1(UUID offerId, String status, UUID issuerId, UUID cardProductId, UUID merchantId,
    String categoryPath, String title, String kind, Integer valueBps, Long valuePaise, Long maxDiscountPaise,
    long minSpendPaise, String validFrom, String validTo, boolean stackable, int version, String changedAt) {
  public static final String TYPE = "catalog.offer.changed";
  public static final int VERSION = 1;
  public static final String TOPIC = "dexter.catalog.offer.v1";
}
