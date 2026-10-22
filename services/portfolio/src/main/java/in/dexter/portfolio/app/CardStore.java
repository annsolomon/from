package in.dexter.portfolio.app;

import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** User cards and the catalog products they refer to (portfolio_app pool, RLS per account). */
public interface CardStore {
  record CardRow(UUID id, UUID accountId, UUID cardProductId, String nickname, String last4, String network,
      int statementDay, int dueOffsetDays, Long creditLimitPaise, LocalDate openedOn, String status, Instant createdAt,
      Instant updatedAt, Instant removedAt, int version) {
    public boolean removed() {
      return "removed".equals(status);
    }
  }

  /** A catalog card as the projection last saw it. */
  record Product(UUID id, String slug, String name, String issuerCode, String network, String tier,
      long annualFeePaise, String status, int currentVersion, String snapshotJson) {}

  void insert(CardRow c);

  /** A card that is not removed. */
  Optional<CardRow> find(UUID id);

  Optional<CardRow> lock(UUID id);

  void update(CardRow c);

  /** Live cards, newest first. */
  Page<CardRow> list(PageRequest page);

  /** True when another live card of the account has this product and last4. */
  boolean exists(UUID cardProductId, String last4, UUID exceptId);

  Optional<Product> product(UUID cardProductId);
}
