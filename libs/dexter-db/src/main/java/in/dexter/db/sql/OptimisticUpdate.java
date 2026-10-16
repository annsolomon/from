package in.dexter.db.sql;

import in.dexter.core.error.NotFoundException;
import in.dexter.core.error.VersionConflictException;
import java.util.function.BooleanSupplier;

/**
 * Interprets the row count of
 * {@code UPDATE ... SET ..., version = version + 1 WHERE id = :id AND version = :expected}
 * (MASTER_PLAN 3.4): 1 = success, 0 = either a version conflict (412) or a missing/invisible row (404).
 */
public final class OptimisticUpdate {
  private OptimisticUpdate() {}

  /**
   * @param updated rows affected by the versioned UPDATE
   * @param exists  checks whether the row is visible at all (same transaction, same RLS context)
   */
  public static void check(int updated, BooleanSupplier exists, String resource, long expectedVersion) {
    if (updated == 1) {
      return;
    }
    if (updated > 1) {
      throw new IllegalStateException("versioned update touched " + updated + " rows of " + resource);
    }
    if (exists.getAsBoolean()) {
      throw new VersionConflictException(resource, expectedVersion);
    }
    throw new NotFoundException(resource);
  }
}
