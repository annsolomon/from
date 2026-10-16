package in.dexter.core.id;

import java.util.Comparator;
import java.util.UUID;

/**
 * Postgres orders {@code uuid} as 16 <em>unsigned</em> bytes; {@link UUID#compareTo} compares two
 * <em>signed</em> longs. The two disagree for any pair whose 64th or 128th bit differs, which is
 * most random UUIDs (proven by {@code UuidsTest}).
 *
 * <p>Wherever code mirrors a database ordering in memory — keyset tie-breakers, merging a fetched
 * page with a local buffer, asserting the order of a result set — it must use
 * {@link #POSTGRES_ORDER}, never the natural order. Paging itself is unaffected: the comparison in
 * {@code KeysetPager}'s SQL is done by Postgres.
 */
public final class Uuids {
  /** Ascending, byte-for-byte as Postgres compares {@code uuid}. */
  public static final Comparator<UUID> POSTGRES_ORDER =
      Comparator.comparing(UUID::getMostSignificantBits, Long::compareUnsigned)
          .thenComparing(UUID::getLeastSignificantBits, Long::compareUnsigned);

  private Uuids() {}

  /** Negative, zero or positive, matching Postgres {@code a < b} / {@code a = b} / {@code a > b}. */
  public static int compare(UUID a, UUID b) {
    return POSTGRES_ORDER.compare(a, b);
  }
}
