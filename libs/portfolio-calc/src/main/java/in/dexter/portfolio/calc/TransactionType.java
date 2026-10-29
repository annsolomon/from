package in.dexter.portfolio.calc;

/** What a {@link LedgerTransaction} does to the lots (old repo {@code calc/ledger/LedgerTypes.TransactionType}). */
public enum TransactionType {
  /** A purchase or cash advance: opens a new lot at the bucket's APR. */
  PURCHASE,
  /** A payment: pays lots down through the allocation order, oldest lot first within a bucket. */
  PAYMENT,
  /**
   * A refund of one identified purchase: reverses that specific lot rather than running the waterfall, because a
   * refund undoes a transaction — it is not the cardholder electing to pay down whatever the allocation order says.
   */
  REFUND_SPECIFIC
}
