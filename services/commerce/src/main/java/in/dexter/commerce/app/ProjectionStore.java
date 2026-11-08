package in.dexter.commerce.app;

import java.util.UUID;

/** The three projections (MASTER_PLAN 6.4: every write guarded on projected_version). Infra implements it. */
public interface ProjectionStore {
  boolean upsertCardProduct(UUID cardProductId, String slug, String name, String issuerCode, String network,
      String status, long version);

  boolean upsertHeldCard(UUID accountId, UUID userCardId, UUID cardProductId, long version);

  boolean deleteHeldCard(UUID accountId, UUID userCardId, long version);

  boolean upsertAdFree(UUID accountId, String tier, boolean adFree, long version);

  /** Whether the current account is ad-free (false when billing has said nothing yet: FREE shows ads). */
  boolean adFree();
}
