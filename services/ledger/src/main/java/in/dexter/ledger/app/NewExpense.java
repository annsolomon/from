package in.dexter.ledger.app;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** POST /v1/expenses body (also the Idempotency-Key fingerprint). */
public record NewExpense(Instant occurredAt, Long amountPaise, String merchant, String categoryPath,
    String paymentMethod, UUID userCardId, String note, List<String> tags, String clientRef) {}
