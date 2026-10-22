package in.dexter.portfolio.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Reducing-balance EMI (the standard Indian card EMI): monthly rate r = annual rate / 12, EMI = P·r·(1+r)^n /
 * ((1+r)^n − 1) (P/n at 0 %), rounded to the paisa (HALF_UP, as issuers quote it). Each instalment's interest is the
 * outstanding × r (HALF_EVEN), its principal the EMI minus that interest; the last instalment takes the remaining
 * principal so the schedule repays P exactly. GST applies to interest (and the processing fee, which the first
 * instalment carries). Pure, exact decimal arithmetic.
 */
public final class EmiMath {
  static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);
  static final BigDecimal TEN_THOUSAND = BigDecimal.valueOf(10_000);

  public record Installment(int number, LocalDate dueDate, long principalPaise, long interestPaise, long gstPaise,
      long totalPaise, long outstandingAfterPaise) {}

  public record Schedule(long emiPaise, long totalInterestPaise, long totalGstPaise, List<Installment> installments) {}

  private EmiMath() {}

  public static long emi(long principalPaise, int annualRateBps, int months) {
    if (principalPaise <= 0 || months < 1 || annualRateBps < 0) {
      throw new IllegalArgumentException("principal > 0, months >= 1, rate >= 0");
    }
    BigDecimal p = BigDecimal.valueOf(principalPaise);
    if (annualRateBps == 0) {
      return p.divide(BigDecimal.valueOf(months), 0, RoundingMode.HALF_UP).longValueExact();
    }
    BigDecimal r = monthlyRate(annualRateBps);
    BigDecimal f = BigDecimal.ONE.add(r).pow(months, MC);
    return p.multiply(r, MC).multiply(f, MC).divide(f.subtract(BigDecimal.ONE), MC)
        .setScale(0, RoundingMode.HALF_UP).longValueExact();
  }

  static BigDecimal monthlyRate(int annualRateBps) {
    return BigDecimal.valueOf(annualRateBps).divide(TEN_THOUSAND, MC).divide(BigDecimal.valueOf(12), MC);
  }

  /** The full schedule; instalment k is due {@code firstDue} plus k−1 months. */
  public static Schedule schedule(long principalPaise, int annualRateBps, int months, int gstBps,
      long processingFeePaise, LocalDate firstDue) {
    long emi = emi(principalPaise, annualRateBps, months);
    BigDecimal r = annualRateBps == 0 ? BigDecimal.ZERO : monthlyRate(annualRateBps);
    BigDecimal gst = BigDecimal.valueOf(gstBps).divide(TEN_THOUSAND, MC);
    long outstanding = principalPaise;
    long totalInterest = 0;
    long totalGst = 0;
    List<Installment> out = new ArrayList<>(months);
    for (int k = 1; k <= months; k++) {
      long interest = BigDecimal.valueOf(outstanding).multiply(r, MC).setScale(0, RoundingMode.HALF_EVEN)
          .longValueExact();
      long principal = k == months ? outstanding : Math.min(outstanding, Math.max(0, emi - interest));
      long fee = k == 1 ? processingFeePaise : 0;
      long g = BigDecimal.valueOf(interest + fee).multiply(gst, MC).setScale(0, RoundingMode.HALF_EVEN)
          .longValueExact();
      outstanding -= principal;
      totalInterest += interest;
      totalGst += g;
      out.add(new Installment(k, firstDue.plusMonths(k - 1), principal, interest, g, principal + interest + fee + g,
          outstanding));
    }
    return new Schedule(emi, totalInterest, totalGst, List.copyOf(out));
  }
}
