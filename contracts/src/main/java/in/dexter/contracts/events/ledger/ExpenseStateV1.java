package in.dexter.contracts.events.ledger;

import java.util.UUID;

/**
 * What a consumer needs to compute deltas from one expense (MASTER_PLAN P07): amount, the local day (Asia/Kolkata)
 * and category it counts under, the card and merchant it is attributed to. {@code userCardId} and
 * {@code merchantId} may be null.
 */
public record ExpenseStateV1(long amountPaise, String day, String categoryPath, String paymentMethod,
    UUID userCardId, UUID merchantId, String occurredAt) {}
