package in.dexter.commerce.app;

import in.dexter.commerce.domain.SavingComponent;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Deals and what the feed reads beside them (the held-card and card-product projections). Infra implements it. */
public interface DealStore {
  record Deal(UUID id, UUID merchantId, String merchantName, String title, String description, String categoryPath,
      List<SavingComponent> components, List<UUID> eligibleCardProducts, long referenceSpendPaise,
      Instant validFrom, @Nullable Instant validTo, String status, int priority, boolean sponsored, int sponsorWeight,
      String network, String landingUrl, int commissionBps, int version, Instant createdAt) {
    public Deal {
      components = List.copyOf(components);
      eligibleCardProducts = List.copyOf(eligibleCardProducts);
    }

    public boolean liveAt(Instant now) {
      return "active".equals(status) && !now.isBefore(validFrom) && (validTo == null || now.isBefore(validTo));
    }
  }

  /** Active deals whose validity covers {@code now}, in the category subtree when given, at most {@code max}. */
  List<Deal> active(@Nullable String category, Instant now, int max);

  Optional<Deal> findDeal(UUID id);

  void insert(Deal deal, @Nullable UUID createdBy);

  /** Compare-and-set on version; false when the version moved (or no such deal). */
  boolean updateStatus(UUID id, String status, int expectedVersion, Instant at);

  /** Operators: every deal, newest first. */
  Page<Deal> adminList(PageRequest page);

  /** The current account's held card products (card-match). */
  Set<UUID> heldCardProducts();

  /** Published card products among {@code ids}, id -> name. */
  Map<UUID, String> publishedCardNames(Collection<UUID> ids);
}
