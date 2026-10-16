package in.dexter.core.id;

import com.github.f4b6a3.uuid.UuidCreator;
import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * UUIDv7 ids generated in the app (MASTER_PLAN 2.3): time-ordered, index-friendly, known before insert.
 */
public final class Ids {
  private static final Pattern CANONICAL =
      Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private Ids() {}

  /** A new UUIDv7 (RFC 9562), monotonic within the JVM. */
  public static UUID newId() {
    return UuidCreator.getTimeOrderedEpoch();
  }

  public static boolean isV7(UUID id) {
    return id != null && id.version() == 7 && id.variant() == 2;
  }

  /** The millisecond timestamp embedded in a UUIDv7. */
  public static Instant timestampOf(UUID v7) {
    if (!isV7(v7)) {
      throw new IllegalArgumentException("not a UUIDv7: " + v7);
    }
    return Instant.ofEpochMilli(v7.getMostSignificantBits() >>> 16);
  }

  /** Strict parse of a client-supplied id; 400 INVALID_ID otherwise (no stack traces to clients). */
  public static UUID parse(String raw, String field) {
    if (raw == null || !CANONICAL.matcher(raw).matches()) {
      throw new DexterException(CoreErrors.INVALID_ID, field + " must be a UUID");
    }
    return UUID.fromString(raw);
  }
}
