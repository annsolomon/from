package in.dexter.billing.domain;

import in.dexter.portfolio.calc.Gst;
import java.util.List;
import java.util.Objects;

/**
 * The mock GST of a plan invoice (MASTER_PLAN P16, ADR-0022 §6). Documented as mock tax logic: it is the shape a real
 * tax engine would fill, not tax advice.
 *
 * <ul>
 *   <li>The tax is 18 % of the plan price, computed once with {@link Gst#on} - the single definition of that rate and
 *       its rounding (HALF_UP to the paisa) that portfolio's fee engines already use.</li>
 *   <li>When the place of supply is the supplier's own state the tax is split into CGST 9 % and SGST 9 %; otherwise it
 *       is IGST 18 %. The split divides the one computed figure (CGST takes the odd paisa), so the customer pays the
 *       same total wherever they are, which is what the law intends and what a per-component rounding would break.</li>
 * </ul>
 */
public final class TaxInvoice {
  public static final int GST_BPS = 1800;
  public static final int HALF_BPS = 900;

  public enum Kind { ITEM, CGST, SGST, IGST }

  public record Line(int lineNo, Kind kind, String description, Integer rateBps, long amountPaise) {}

  /** {@code regime} is {@code intra} (CGST + SGST) or {@code inter} (IGST). */
  public record Result(String regime, List<Line> lines, long subtotalPaise, long taxPaise, long totalPaise) {}

  private TaxInvoice() {}

  /** The tax a price carries (what the checkout charges on top of the plan price). */
  public static long gstOn(long taxablePaise) {
    if (taxablePaise < 0) {
      throw new IllegalArgumentException("taxable amount must not be negative");
    }
    return Gst.on(taxablePaise);
  }

  public static Result of(String description, long taxablePaise, String supplierState, String placeOfSupply) {
    Objects.requireNonNull(description, "description");
    if (!IndianStates.valid(supplierState) || !IndianStates.valid(placeOfSupply)) {
      throw new IllegalArgumentException("unknown state code");
    }
    long gst = gstOn(taxablePaise);
    Line item = new Line(1, Kind.ITEM, description, null, taxablePaise);
    if (supplierState.equals(placeOfSupply)) {
      long cgst = (gst + 1) / 2; // the odd paisa, if any, goes to CGST
      long sgst = gst - cgst;
      return new Result("intra", List.of(item, new Line(2, Kind.CGST, "CGST 9%", HALF_BPS, cgst),
          new Line(3, Kind.SGST, "SGST 9%", HALF_BPS, sgst)), taxablePaise, gst, taxablePaise + gst);
    }
    return new Result("inter", List.of(item, new Line(2, Kind.IGST, "IGST 18%", GST_BPS, gst)), taxablePaise, gst,
        taxablePaise + gst);
  }
}
