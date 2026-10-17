package in.dexter.ledger.app;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * JSON Merge Patch of an expense (RFC 7386): a field is either absent (unchanged) or present with a value or null.
 */
public record ExpensePatch(Field<Instant> occurredAt, Field<Long> amountPaise, Field<String> merchant,
    Field<String> categoryPath, Field<String> paymentMethod, Field<UUID> userCardId, Field<String> note,
    Field<List<String>> tags) {

  /** {@code present=false}: absent from the patch. */
  public record Field<T>(boolean present, T value) {
    private static final Field<?> ABSENT = new Field<>(false, null);

    @SuppressWarnings("unchecked")
    public static <T> Field<T> absent() {
      return (Field<T>) ABSENT;
    }

    public static <T> Field<T> of(T value) {
      return new Field<>(true, value);
    }
  }
}
