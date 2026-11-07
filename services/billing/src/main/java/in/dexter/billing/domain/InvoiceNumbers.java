package in.dexter.billing.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Invoice numbers: {@code DX/<financial year>/<serial>}, e.g. {@code DX/2026-27/000042}. The Indian financial year
 * runs April to March in IST. The serial comes from one database sequence, so numbers are unique across years too
 * (GST asks for a unique serial per financial year; a global one satisfies it, at the cost of not restarting at 1).
 */
public final class InvoiceNumbers {
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private InvoiceNumbers() {}

  /** "2026-27" for any day from 2026-04-01 to 2027-03-31 (IST). */
  public static String financialYear(Instant issuedAt) {
    LocalDate d = issuedAt.atZone(IST).toLocalDate();
    int start = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
    return start + "-" + String.format("%02d", (start + 1) % 100);
  }

  public static String format(Instant issuedAt, long serial) {
    if (serial < 1) {
      throw new IllegalArgumentException("serial must be positive");
    }
    return "DX/" + financialYear(issuedAt) + "/" + String.format("%06d", serial);
  }
}
