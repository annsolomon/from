package in.dexter.contracts.events.portfolio;

import java.util.UUID;

/**
 * {@code portfolio.card.added|updated|removed} v1 (MASTER_PLAN P11) on {@code dexter.portfolio.card.v1} (key =
 * account id): the user card after the change ({@code status} {@code removed} on removed). {@code version} is the
 * card's version (also the envelope's aggregateVersion).
 */
public record CardChangedV1(UUID userCardId, UUID cardProductId, String nickname, String last4, String network,
    int statementDay, String status, int version, String changedAt) {
  public static final String ADDED = "portfolio.card.added";
  public static final String UPDATED = "portfolio.card.updated";
  public static final String REMOVED = "portfolio.card.removed";
  public static final int VERSION = 1;
  public static final String TOPIC = "dexter.portfolio.card.v1";
}
