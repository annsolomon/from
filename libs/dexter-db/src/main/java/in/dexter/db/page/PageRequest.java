package in.dexter.db.page;

import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;

/** Validated {@code limit} + {@code cursor} (MASTER_PLAN 4.1): default 25, 1..100. */
public record PageRequest(int limit, String cursor) {
  public static final int DEFAULT_LIMIT = 25;
  public static final int MAX_LIMIT = 100;

  public PageRequest {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new DexterException(CoreErrors.LIMIT_INVALID, "limit must be between 1 and " + MAX_LIMIT);
    }
    cursor = cursor == null || cursor.isBlank() ? null : cursor;
  }

  /** From raw query parameters; a null limit means the default. */
  public static PageRequest of(Integer limit, String cursor) {
    return new PageRequest(limit == null ? DEFAULT_LIMIT : limit, cursor);
  }

  public static PageRequest first(int limit) {
    return new PageRequest(limit, null);
  }

  public boolean isFirst() {
    return cursor == null;
  }
}
