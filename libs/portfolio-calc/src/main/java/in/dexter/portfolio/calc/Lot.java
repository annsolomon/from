package in.dexter.portfolio.calc;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One lot of outstanding balance — a purchase, a cash advance, or a carried-forward finance charge — tracked
 * separately because each can carry its own APR and is paid down independently by the waterfall (old repo
 * {@code calc/ledger/Lot}).
 *
 * <p>Mutable and in rupees, exactly as the original: the waterfall and the daily-interest loop both shrink
 * {@code remaining} in place across many iterations. It stays package-private in this port — the library's public
 * surface is paise ({@link LedgerEngine.LotView}), and nothing outside this package needs a handle it could mutate.
 */
final class Lot {
  LocalDate date;
  String bucket;
  BigDecimal remaining;
  BigDecimal apr;
  /** Null unless the lot came from a purchase that carried an issuer reference. */
  String referenceNumber;

  Lot(LocalDate date, String bucket, BigDecimal remaining, BigDecimal apr, String referenceNumber) {
    this.date = date;
    this.bucket = bucket;
    this.remaining = remaining;
    this.apr = apr;
    this.referenceNumber = referenceNumber;
  }

  Lot copy() {
    return new Lot(date, bucket, remaining, apr, referenceNumber);
  }

  /** Same lot, re-dated — used when rolling a cycle's closing lots into the next cycle's opening lots. */
  Lot copyWithDate(LocalDate newDate) {
    return new Lot(newDate, bucket, remaining, apr, referenceNumber);
  }

  boolean open() {
    return remaining.compareTo(BigDecimal.ZERO) > 0;
  }
}
