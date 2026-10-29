package in.dexter.portfolio.calc;

import java.time.LocalDate;

/**
 * One transaction inside a billing cycle (old repo {@code calc/ledger/LedgerTypes.LedgerTransaction}).
 *
 * @param date the day it posts; transactions apply before that day's interest accrues
 * @param type see {@link TransactionType}
 * @param amountPaise always positive — the type says which direction it moves the balance
 * @param bucket for a {@code PURCHASE}: {@code retail_purchases} (the default when null) or {@code cash_advances}
 * @param referenceNumber the issuer's reference for a purchase, so a later refund can name it; may be null
 * @param originalReferenceNumber for a {@code REFUND_SPECIFIC}: the {@code referenceNumber} it reverses
 */
public record LedgerTransaction(LocalDate date, TransactionType type, long amountPaise, String bucket,
    String referenceNumber, String originalReferenceNumber) {

  public LedgerTransaction {
    if (date == null || type == null) {
      throw new IllegalArgumentException("date and type are required");
    }
    if (amountPaise < 0) {
      throw new IllegalArgumentException("amount must not be negative");
    }
  }

  /** A plain retail purchase. */
  public static LedgerTransaction purchase(LocalDate date, long amountPaise) {
    return new LedgerTransaction(date, TransactionType.PURCHASE, amountPaise, "retail_purchases", null, null);
  }

  /** A payment, which the allocation order allocates. */
  public static LedgerTransaction payment(LocalDate date, long amountPaise) {
    return new LedgerTransaction(date, TransactionType.PAYMENT, amountPaise, null, null, null);
  }
}
