package in.dexter.portfolio.app;

import java.util.UUID;

/** The catalog card projection (global; written only by the PROJECTOR actor). */
public interface ProjectionStore {
  /** Writes the product when {@code version} is newer than the stored projected version. */
  boolean upsertProduct(CardStore.Product p, long version);

  long projectedVersion(UUID cardProductId);
}
