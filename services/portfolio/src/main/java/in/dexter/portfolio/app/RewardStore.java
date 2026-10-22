package in.dexter.portfolio.app;

import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The reward points ledger (append-only; portfolio_app pool, RLS per account). */
public interface RewardStore {
  record Entry(UUID id, UUID accountId, UUID userCardId, String kind, long points, long valueMilliPaise,
      LocalDate expiresOn, String sourceRef, UUID cardTxnId, String note, Instant createdAt) {}

  /** Appends; false when an entry with the same source ref exists (idempotent earn). */
  boolean append(Entry e);

  /** Newest first; one card or all. */
  Page<Entry> list(PageRequest page, UUID cardId);

  /** A card's entries in posting order (the balance). */
  List<Entry> entries(UUID cardId);

  /** The card ids with any entry. */
  List<UUID> cardsWithPoints();
}
