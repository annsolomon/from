package in.dexter.ledger.app;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One {@code ledger.expenses} row (also the API view's source). */
public record ExpenseRow(UUID id, UUID accountId, Instant occurredAt, long amountPaise, String currency,
    String merchantRaw, String merchantDisplay, UUID merchantId, String categoryPath, String categorySource,
    Integer resolutionConfidence, String paymentMethod, UUID userCardId, String note, List<String> tags,
    String clientRef, String source, Instant createdAt, Instant updatedAt, long version, Instant deletedAt) {

  public ExpenseRow {
    tags = tags == null ? List.of() : List.copyOf(tags);
  }

  public boolean deleted() {
    return deletedAt != null;
  }
}
