package in.dexter.core.error;

import java.util.List;
import java.util.Map;

/** 412 VERSION_CONFLICT: optimistic-lock miss (MASTER_PLAN 3.4). */
public class VersionConflictException extends DexterException {
  public VersionConflictException(String resource, long expectedVersion) {
    super(CoreErrors.VERSION_CONFLICT, resource + " was modified by another request",
        List.of(), Map.of("expectedVersion", expectedVersion), null);
  }
}
