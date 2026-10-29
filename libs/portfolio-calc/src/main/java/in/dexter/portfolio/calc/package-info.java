/**
 * portfolio-calc: the credit-card calculation engines, pure Java, no Spring (MASTER_PLAN P12).
 *
 * <p>Ported in P12b from the old repo's {@code card-portfolio-service/.../portfolio/calc} package, which was itself a
 * port of the original Node calculation-engine. The old tests came across as golden fixtures with their values
 * converted to paise, and all of them pass unchanged — see ADR-0018 for the port's rules and the one engine
 * deliberately left behind.
 *
 * <p>Conventions: money is {@code long} paise and rates are {@code int} basis points at every boundary; inside, the
 * arithmetic is BigDecimal rupees with the verified original's rounding ({@code setScale(2, HALF_UP)} at the same
 * points), which is what makes the fixtures hold to the paisa. Every engine is a final class of static methods with
 * no state, no clock and no I/O; {@link in.dexter.portfolio.calc.LedgerEngine} is the one the others build on.
 */
package in.dexter.portfolio.calc;
