package in.dexter.seed;

import java.util.UUID;

/**
 * Deterministic UUIDv7 (RFC 9562 §5.7): 48-bit Unix milliseconds, version 7, 12 + 62 random bits from a seeded
 * stream. Time-ordered like the ids services will mint ({@code Ids} in dexter-core), so the seed exercises the same
 * index locality.
 */
public final class SeedIds {
  private SeedIds() {}

  public static UUID v7(long epochMillis, Rng rng) {
    if (epochMillis < 0 || epochMillis >= (1L << 48)) {
      throw new IllegalArgumentException("timestamp out of UUIDv7 range: " + epochMillis);
    }
    long r1 = rng.nextLong();
    long r2 = rng.nextLong();
    long msb = (epochMillis << 16) | 0x7000L | (r1 & 0x0FFFL);
    long lsb = (r2 & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
    return new UUID(msb, lsb);
  }

  /** Millisecond timestamp encoded in a v7 id. */
  public static long millisOf(UUID id) {
    return id.getMostSignificantBits() >>> 16;
  }
}
