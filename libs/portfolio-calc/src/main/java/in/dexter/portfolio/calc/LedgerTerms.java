package in.dexter.portfolio.calc;

import java.util.List;

/**
 * The interest rates and payment-allocation order the ledger engine reads (old repo
 * {@code calc/ledger/LedgerTypes.LedgerTerms}).
 *
 * <p>Deliberately narrower than the old JS source's full {@code calculationTerms} schema — it carries only what
 * {@link LedgerEngine} actually reads. Carried over as the original stated it: grace-period day counts and the rest
 * of that schema are not modelled here. Custom fees, which the old port also left out of the simulators, ARE
 * modelled in this port ({@link CustomFeeCalculator}) and wired into {@link FullPaymentCalculator}.
 *
 * @param aprBps retail APR in basis points (4200 = 42 %)
 * @param cashAdvanceAprBps cash-advance APR in basis points; cash advances never get grace
 * @param paymentAllocationOrder buckets in the order a payment pays them down, e.g.
 *     {@code [retail_purchases, finance_charges, fees]}
 */
public record LedgerTerms(int aprBps, int cashAdvanceAprBps, List<String> paymentAllocationOrder) {

  public LedgerTerms {
    if (aprBps < 0 || cashAdvanceAprBps < 0) {
      throw new IllegalArgumentException("APR must not be negative");
    }
    paymentAllocationOrder = List.copyOf(paymentAllocationOrder);
    if (paymentAllocationOrder.isEmpty()) {
      throw new IllegalArgumentException("paymentAllocationOrder must not be empty");
    }
  }
}
