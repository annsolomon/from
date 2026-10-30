package in.dexter.portfolio.api;

import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.portfolio.app.CalcService;
import in.dexter.portfolio.calc.CustomFeeCalculator.CustomFeesResult;
import in.dexter.portfolio.calc.ExitPathCalculator.SafeExitPathsResult;
import in.dexter.portfolio.calc.FullPaymentCalculator.FullPaymentResult;
import in.dexter.portfolio.calc.LedgerEngine.LedgerResult;
import in.dexter.portfolio.calc.MinimumPaymentSimulator.MinimumPaymentResult;
import in.dexter.portfolio.calc.NoPaymentSimulator.NoPaymentResult;
import in.dexter.portfolio.calc.PartialPaymentCalculator.PartialPaymentResult;
import in.dexter.portfolio.calc.RepaymentPathCalculator.BestPathResult;
import in.dexter.portfolio.calc.EmiMath;
import in.dexter.security.AuthContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/calc (MASTER_PLAN P12 step 2): the ported calc engines as stateless endpoints — POST only, no database, no
 * stored state, rate-limited as the gateway's {@code calc} class (60 a minute per account). Every answer is a pure
 * function of the body, so nothing here is idempotency-keyed, ETagged or paginated.
 *
 * <p>Bodies are capped: these run loops over days, cycles and lots for any signed-in caller.
 */
@RestController
@RequestMapping(CalcService.PATH)
public class CalcController {

  /** No calc request needs more than this; the engines' own list caps are in {@link CalcService}. */
  static final long MAX_BODY_BYTES = 64L * 1024L;

  private static final String LENGTH = "Content-Length";

  private final CalcService calc;

  public CalcController(CalcService calc) {
    this.calc = calc;
  }

  @PostMapping("/emi")
  public EmiMath.Schedule emi(AuthContext caller, @RequestHeader(name = LENGTH, required = false) Long length,
      @RequestBody CalcService.EmiReq body) {
    check(caller, length);
    return calc.emi(body);
  }

  @PostMapping("/ledger")
  public LedgerResult ledger(AuthContext caller, @RequestHeader(name = LENGTH, required = false) Long length,
      @RequestBody CalcService.LedgerReq body) {
    check(caller, length);
    return calc.ledger(body);
  }

  @PostMapping("/minimum-due")
  public CalcService.MinimumDueResult minimumDue(AuthContext caller,
      @RequestHeader(name = LENGTH, required = false) Long length, @RequestBody CalcService.MinimumDueReq body) {
    check(caller, length);
    return calc.minimumDue(body);
  }

  @PostMapping("/late-fee")
  public CalcService.LateFeeResult lateFee(AuthContext caller,
      @RequestHeader(name = LENGTH, required = false) Long length, @RequestBody CalcService.LateFeeReq body) {
    check(caller, length);
    return calc.lateFee(body);
  }

  @PostMapping("/utilization")
  public CalcService.UtilizationAndFeeResult utilization(AuthContext caller,
      @RequestHeader(name = LENGTH, required = false) Long length, @RequestBody CalcService.UtilizationReq body) {
    check(caller, length);
    return calc.utilization(body);
  }

  @PostMapping("/custom-fees")
  public CustomFeesResult customFees(AuthContext caller,
      @RequestHeader(name = LENGTH, required = false) Long length, @RequestBody CalcService.CustomFeesReq body) {
    check(caller, length);
    return calc.customFees(body);
  }

  @PostMapping("/scenarios/full-payment")
  public FullPaymentResult fullPayment(AuthContext caller,
      @RequestHeader(name = LENGTH, required = false) Long length, @RequestBody CalcService.FullPaymentReq body) {
    check(caller, length);
    return calc.fullPayment(body);
  }

  @PostMapping("/scenarios/partial-payment")
  public PartialPaymentResult partialPayment(AuthContext caller,
      @RequestHeader(name = LENGTH, required = false) Long length, @RequestBody CalcService.PartialPaymentReq body) {
    check(caller, length);
    return calc.partialPayment(body);
  }

  @PostMapping("/scenarios/no-payment")
  public NoPaymentResult noPayment(AuthContext caller,
      @RequestHeader(name = LENGTH, required = false) Long length, @RequestBody CalcService.NoPaymentReq body) {
    check(caller, length);
    return calc.noPayment(body);
  }

  @PostMapping("/scenarios/minimum-payment")
  public MinimumPaymentResult minimumPayment(AuthContext caller,
      @RequestHeader(name = LENGTH, required = false) Long length, @RequestBody CalcService.MinimumPaymentReq body) {
    check(caller, length);
    return calc.minimumPayment(body);
  }

  @PostMapping("/repayment-path")
  public BestPathResult repaymentPath(AuthContext caller,
      @RequestHeader(name = LENGTH, required = false) Long length, @RequestBody CalcService.RepaymentPathReq body) {
    check(caller, length);
    return calc.repaymentPath(body);
  }

  @PostMapping("/exit-paths")
  public SafeExitPathsResult exitPaths(AuthContext caller,
      @RequestHeader(name = LENGTH, required = false) Long length, @RequestBody CalcService.ExitPathsReq body) {
    check(caller, length);
    return calc.exitPaths(body);
  }

  /** A user token (not an admin-console one), and a body within the cap. */
  private static void check(AuthContext caller, Long contentLength) {
    Callers.user(caller);
    if (contentLength != null && contentLength > MAX_BODY_BYTES) {
      throw new DexterException(CoreErrors.PAYLOAD_TOO_LARGE,
          "A calc body is at most " + MAX_BODY_BYTES + " bytes");
    }
  }
}
