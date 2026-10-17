package in.dexter.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * One CSV import row (MASTER_PLAN P08), parsed from the staged text columns
 * {@code date,amount,merchant,category,payment_method,note,client_ref}. Pure: the processor checks visibility of the
 * category and resolves the merchant afterwards.
 *
 * <ul>
 *   <li>date: {@code 2026-09-14}, {@code 14/09/2026} (day first, as Indian statements print it) — noon IST — or an
 *       ISO-8601 instant/offset date-time;</li>
 *   <li>amount: rupees, positive, at most 2 decimals, optional {@code ₹}/{@code INR}/{@code Rs} and thousands
 *       commas ({@code 1,23,456.50});</li>
 *   <li>payment_method: one of {@link PaymentMethod} (default {@code other}).</li>
 * </ul>
 */
public final class ImportRow {
  public static final long MAX_AMOUNT_PAISE = 100_000_000_000L;
  private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("d/M/uuuu");
  private static final LocalTime NOON = LocalTime.NOON;

  /** A valid row. {@code category} and {@code merchant} may be null. */
  public record Parsed(Instant occurredAt, long amountPaise, String merchant, String category, String paymentMethod,
      String note, String clientRef) {}

  /** {@code ok} or one error code + detail. */
  public record Result(Parsed row, String code, String detail) {
    public boolean ok() {
      return row != null;
    }

    static Result error(String code, String detail) {
      return new Result(null, code, detail);
    }
  }

  private ImportRow() {}

  public static Result parse(String date, String amount, String merchant, String category, String paymentMethod,
      String note, String clientRef) {
    Instant at = date(date);
    if (at == null) {
      return Result.error("DATE_INVALID", "Use YYYY-MM-DD, DD/MM/YYYY or an ISO-8601 date-time");
    }
    Long paise = paise(amount);
    if (paise == null) {
      return Result.error("AMOUNT_INVALID", "A positive rupee amount with at most 2 decimals");
    }
    String m = blankToNull(merchant);
    if (m != null && m.codePointCount(0, m.length()) > 120) {
      return Result.error("MERCHANT_TOO_LONG", "At most 120 characters");
    }
    String c = blankToNull(category);
    if (c != null) {
      c = c.toLowerCase(Locale.ROOT);
      if (!CategoryPaths.isValid(c)) {
        return Result.error("CATEGORY_INVALID", "Not a category path");
      }
    }
    String pm = blankToNull(paymentMethod);
    PaymentMethod method = pm == null ? PaymentMethod.OTHER : PaymentMethod.parse(pm.toLowerCase(Locale.ROOT));
    if (method == null) {
      return Result.error("PAYMENT_METHOD_INVALID", "One of card, upi, cash, netbanking, wallet, other");
    }
    String n = blankToNull(note);
    if (n != null && n.codePointCount(0, n.length()) > 500) {
      return Result.error("NOTE_TOO_LONG", "At most 500 characters");
    }
    String ref = blankToNull(clientRef);
    if (ref != null && ref.length() > 100) {
      return Result.error("CLIENT_REF_TOO_LONG", "At most 100 characters");
    }
    return new Result(new Parsed(at, paise, m, c, method.db(), n, ref), null, null);
  }

  static Instant date(String s) {
    String v = blankToNull(s);
    if (v == null) {
      return null;
    }
    try {
      if (v.length() == 10 && v.charAt(4) == '-') {
        return LocalDate.parse(v).atTime(NOON).atZone(LedgerTime.ZONE).toInstant();
      }
      if (v.indexOf('/') > 0 && v.length() <= 10) {
        return LocalDate.parse(v, DMY).atTime(NOON).atZone(LedgerTime.ZONE).toInstant();
      }
      if (v.endsWith("Z")) {
        return Instant.parse(v);
      }
      return OffsetDateTime.parse(v).toInstant();
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  static Long paise(String s) {
    String v = blankToNull(s);
    if (v == null) {
      return null;
    }
    v = v.replace("₹", "").replaceFirst("(?i)^(inr|rs\\.?)\\s*", "").replace(",", "").strip();
    if (!v.matches("\\d{1,12}(\\.\\d{1,2})?")) {
      return null;
    }
    long p = new BigDecimal(v).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    return p < 1 || p > MAX_AMOUNT_PAISE ? null : p;
  }

  /**
   * The natural key of a row without a {@code client_ref}: a hash of its content and how many identical rows came
   * before it in the same file, so uploading the same file again inserts nothing (MASTER_PLAN P08: re-import is
   * idempotent), while two genuinely identical expenses in one file both count.
   */
  public static String derivedRef(Parsed r, int occurrence) {
    String canonical = r.occurredAt() + "|" + r.amountPaise() + "|" + nz(r.merchant()) + "|" + nz(r.category()) + "|"
        + r.paymentMethod() + "|" + nz(r.note()) + "#" + occurrence;
    try {
      byte[] h = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return "imp-" + HexFormat.of().formatHex(h, 0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The content part of {@link #derivedRef} (rows with equal keys are counted as occurrences). */
  public static String contentKey(Parsed r) {
    return r.occurredAt() + "|" + r.amountPaise() + "|" + nz(r.merchant()) + "|" + nz(r.category()) + "|"
        + r.paymentMethod() + "|" + nz(r.note());
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }

  private static String blankToNull(String s) {
    if (s == null) {
      return null;
    }
    String t = s.strip();
    return t.isEmpty() ? null : t;
  }
}
