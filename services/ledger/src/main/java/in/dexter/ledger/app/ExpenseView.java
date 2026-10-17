package in.dexter.ledger.app;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** API representation of an expense (camelCase, MASTER_PLAN 9). The version is also the ETag. */
public record ExpenseView(UUID id, Instant occurredAt, long amountPaise, String currency, String merchant,
    UUID merchantId, String categoryPath, String categorySource, Integer resolutionConfidence, String paymentMethod,
    UUID userCardId, String note, List<String> tags, String clientRef, String source, Instant createdAt,
    Instant updatedAt, long version) {

  static ExpenseView of(ExpenseRow r) {
    return new ExpenseView(r.id(), r.occurredAt(), r.amountPaise(), r.currency(),
        r.merchantDisplay() != null ? r.merchantDisplay() : r.merchantRaw(), r.merchantId(), r.categoryPath(),
        r.categorySource(), r.resolutionConfidence(), r.paymentMethod(), r.userCardId(), r.note(), r.tags(),
        r.clientRef(), r.source(), r.createdAt(), r.updatedAt(), r.version());
  }
}
