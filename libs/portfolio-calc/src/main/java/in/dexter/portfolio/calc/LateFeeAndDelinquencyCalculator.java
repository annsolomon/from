package in.dexter.portfolio.calc;

import java.util.List;

/**
 * The late-fee tier table and the RBI delinquency staging (old repo {@code calc/LateFeeAndDelinquencyCalculator},
 * itself a port of {@code lateFeeAndDelinquency.js}).
 *
 * <p>The stage thresholds and the RBI terminology (SMA-0/SMA-1/SMA-2/NPA, credit-bureau reporting) are carried over
 * verbatim: this is real Indian-lending domain logic, not placeholder text.
 */
public final class LateFeeAndDelinquencyCalculator {

  private LateFeeAndDelinquencyCalculator() {}

  /**
   * One row of an issuer's late-fee schedule, both bounds inclusive.
   *
   * @param maxOutstandingPaise null means no upper bound — the top tier
   */
  public record LateFeeTier(long minOutstandingPaise, Long maxOutstandingPaise, long lateFeePaise) {}

  public enum DelinquencyStage { CURRENT, SMA_0, SMA_1, SMA_2, NPA }

  /**
   * @param reportedToBureaus whether this stage is reported to the credit bureaus
   * @param severelyDamaging NPA only — the stage that does lasting damage to a credit file
   */
  public record DelinquencyClassification(DelinquencyStage stage, String label, boolean reportedToBureaus,
      boolean severelyDamaging) {}

  /** The first tier the balance falls into; zero when nothing is outstanding or no tier matches. */
  public static long lateFee(long outstandingBalancePaise, List<LateFeeTier> tiers) {
    if (outstandingBalancePaise <= 0) {
      return 0L;
    }
    return tiers.stream()
        .filter(t -> outstandingBalancePaise >= t.minOutstandingPaise()
            && (t.maxOutstandingPaise() == null || outstandingBalancePaise <= t.maxOutstandingPaise()))
        .findFirst()
        .map(LateFeeTier::lateFeePaise)
        .orElse(0L);
  }

  public static DelinquencyClassification classifyDelinquencyStage(int daysOverdue) {
    if (daysOverdue <= 0) {
      return new DelinquencyClassification(DelinquencyStage.CURRENT, "No overdue amount", false, false);
    }
    if (daysOverdue <= 30) {
      return new DelinquencyClassification(DelinquencyStage.SMA_0,
          "Up to 30 days overdue — flagged as Special Mention Account", true, false);
    }
    if (daysOverdue <= 60) {
      return new DelinquencyClassification(DelinquencyStage.SMA_1,
          "30-60 days overdue — escalated Special Mention Account", true, false);
    }
    if (daysOverdue <= 90) {
      return new DelinquencyClassification(DelinquencyStage.SMA_2,
          "60-90 days overdue — final stage before NPA classification", true, false);
    }
    return new DelinquencyClassification(DelinquencyStage.NPA,
        "90+ days overdue — classified as Non-Performing Asset", true, true);
  }
}
