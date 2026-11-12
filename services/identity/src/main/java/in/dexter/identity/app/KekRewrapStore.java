package in.dexter.identity.app;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** Wrapped keys not under the active KEK, and a compare-and-set swap (P19, ADR-0026). */
public interface KekRewrapStore {
  /** One wrapped key: what it is ({@code account_key}, {@code signing_key}, {@code admin_totp}), its row id. */
  record Wrapped(String kind, String id, String kekId, byte[] sealed) {}

  /** Up to {@code limit} per kind not under {@code active}; only those under {@code from} when it is set. */
  List<Wrapped> stale(String active, @Nullable String from, int limit);

  /** Replaces the wrapped key if it is still under {@code oldKek}; false when it moved meanwhile. */
  boolean swap(Wrapped row, String newKek, byte[] sealed);

  /** Rows per kind and KEK id. */
  List<Usage> usage();

  record Usage(String kind, String kekId, long rows) {}
}
