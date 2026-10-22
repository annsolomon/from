package in.dexter.contracts.events.ledger;

import java.util.UUID;

/**
 * What a consumer needs to compute deltas from one expense (MASTER_PLAN P07): amount, the local day (Asia/Kolkata)
 * and category it counts under, the card and merchant it is attributed to. {@code userCardId}, {@code merchantId}
 * and {@code merchant} (the display name: the resolved merchant's name, or the text the user typed) may be null.
 * {@code merchant} was added in P11 (portfolio's auto-match is the first consumer, A-62).
 */
public record ExpenseStateV1(long amountPaise, String day, String categoryPath, String paymentMethod,
    UUID userCardId, UUID merchantId, String merchant, String occurredAt) {}
