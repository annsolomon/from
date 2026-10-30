package in.dexter.planning.domain;

import in.dexter.portfolio.calc.EmiMath;
import java.time.LocalDate;
import java.util.List;

/**
 * A liability's monthly instalment and its amortization schedule (MASTER_PLAN P13), on the shared EMI engine
 * ({@code libs/portfolio-calc}, A-74) — the same reducing-balance maths portfolio's card EMI plans use, and the one
 * that reproduces the old {@code budget-service} reference of ₹8,884.88.
 *
 * <p>No GST here: a loan's interest carries none, and the GST a credit-card EMI does carry is modelled where those
 * plans live, in portfolio's {@code emi_plans}. A liability's {@code start_date} is its first instalment's due date.
 */
public final class LiabilityMath {

  private LiabilityMath() {}

  /** One instalment of the stored schedule. */
  public record Installment(int number, LocalDate dueOn, long principalPaise, long interestPaise, long totalPaise,
      long outstandingAfterPaise) {}

  public record Schedule(long emiPaise, long totalInterestPaise, long totalPayablePaise,
      List<Installment> installments) {}

  /** The monthly instalment the terms imply. */
  public static long emi(long principalPaise, int aprBps, int tenureMonths) {
    return EmiMath.emi(principalPaise, aprBps, tenureMonths);
  }

  public static Schedule schedule(long principalPaise, int aprBps, int tenureMonths, LocalDate firstDue) {
    EmiMath.Schedule s = EmiMath.schedule(principalPaise, aprBps, tenureMonths, 0, 0L, firstDue);
    List<Installment> out = s.installments().stream()
        .map(i -> new Installment(i.number(), i.dueDate(), i.principalPaise(), i.interestPaise(),
            i.principalPaise() + i.interestPaise(), i.outstandingAfterPaise()))
        .toList();
    long payable = out.stream().mapToLong(Installment::totalPaise).sum();
    return new Schedule(s.emiPaise(), s.totalInterestPaise(), payable, out);
  }

  /** What is still owed after {@code paid} instalments, straight off the schedule. */
  public static long outstandingAfter(Schedule schedule, int paid) {
    if (paid <= 0) {
      return schedule.installments().isEmpty() ? 0
          : schedule.installments().getFirst().outstandingAfterPaise()
              + schedule.installments().getFirst().principalPaise();
    }
    List<Installment> rows = schedule.installments();
    return paid >= rows.size() ? 0 : rows.get(paid - 1).outstandingAfterPaise();
  }
}
