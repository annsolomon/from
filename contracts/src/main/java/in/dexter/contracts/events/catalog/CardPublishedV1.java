package in.dexter.contracts.events.catalog;

import java.util.Map;
import java.util.UUID;

/**
 * {@code catalog.card.published} / {@code catalog.card.retired} v1 (MASTER_PLAN P09) on the compacted topic
 * {@code dexter.catalog.card.v1} (key = card id): the full published snapshot of a card product including its reward
 * rules and milestone, so a consumer can rebuild its projection from the latest event per card. {@code version} is
 * the catalog version (1, 2, ...); the envelope's aggregateVersion orders events per card. {@code effectiveTo} is set
 * on retired.
 */
public record CardPublishedV1(UUID cardId, String slug, String status, int version, String effectiveFrom,
    String effectiveTo, Map<String, Object> snapshot) {
  public static final String PUBLISHED = "catalog.card.published";
  public static final String RETIRED = "catalog.card.retired";
  public static final int VERSION = 1;
  public static final String TOPIC = "dexter.catalog.card.v1";
}
