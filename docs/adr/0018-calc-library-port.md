# ADR-0018: The calc library — porting the old engines, and the paise boundary

- Status: Accepted (P12b). Proof: `libs/portfolio-calc` unit tests (the 31 ported golden fixtures plus 9 boundary
  tests), `CalcIT`, api-smoke P12 calc block, trail-check `p12-calc`.
- Context: MASTER_PLAN P12 step 1 asks for **every** old calc engine (§12's list) ported to pure Java in
  `libs/portfolio-calc`, paise `long` at the boundaries and BigDecimal internally "with the same rounding the old
  verified code used", **all** the old tests carried over as golden fixtures with their values converted to paise,
  and any mismatch explained in an ADR rather than silently "fixed". The DoD is "100 % of the old calc tests ported
  and green". A-68 split P12 because the old repo was not available in the P12a chat; this is the other half.

## Decisions

1. **The arithmetic stays in BigDecimal rupees; only the boundary is paise.** The old engines rounded money with
   exactly one operation, `round2 = setScale(2, HALF_UP)`, at specific points in each formula. On rupees that
   operation *is* "round to the paisa", so keeping the internals in rupees puts every one of those rounding points
   on the same value as the verified original. Converting the internals to paise instead would move each of them two
   decimal places and change results in the last paisa — which is precisely what the golden fixtures exist to catch.
   `Money` holds the boundary: `rupees(long)` is exact (a paisa is 0.01 rupees), `paise(BigDecimal)` rounds only
   what the original already rounded, and `percent(int bps)` is exact (4200 bps → `42.00`, numerically the `42` the
   original carried).
2. **Rates are basis points at the boundary.** `aprBps`, `percentBps`, `utilizationBps`: v2's convention
   (dexter-core, `EmiMath`). Every conversion is exact, and a computed percentage comes back as bps because 1 bp is
   0.01 % — the two decimals the original rounded a percentage to.
3. **Every engine is a final class of static methods.** The originals were Spring `@Component`s wired into each
   other; the module must never see Spring (P00's note, and the enforcer). They have no state, no clock and no I/O,
   so static functions are the honest shape, and `LedgerEngine` is the one the others call.
4. **`Lot` stays mutable and package-private.** The waterfall and the daily-interest loop both shrink a lot's
   remaining balance in place across many iterations; that is the original's design and the ported interest numbers
   depend on it. Nothing outside the library gets a handle it could mutate: the public surface is
   `LedgerEngine.LotView`, in paise.
5. **All 31 old calc tests are ported, and all 31 pass unchanged.** No value needed explaining: the 141-cycle
   minimum-payment case (₹26,119.42 interest, ₹4,701.46 GST, ₹30,820.88 total), the exit-path binary search
   (₹2,141.01 a cycle, ₹359.15 interest, ₹64.65 GST), the no-payment projections and the avalanche/snowball
   comparison all reproduce to the paisa. The test classes keep the old names so the port can be audited against the
   old repo file by file.
6. **`emi/CardEmiCalculator` is deliberately *not* ported, and `/v1/calc/emi` is served by P11's `EmiMath`.**
   MASTER_PLAN §12 assigns `calc/*` to P12 and the other EMI calculator (`budget-service`) to P13;
   `emi/CardEmiCalculator` sat outside the calc package and is superseded. P11 already built `EmiMath` as v2's EMI
   engine (ADR-0016) and `emi_plans` runs on it, so a second EMI implementation would mean two different answers to
   the same question in one service. They genuinely differ: the old class rounded the instalment with `CEILING` and
   each instalment's interest `HALF_UP`, while `EmiMath` rounds the instalment `HALF_UP` and interest `HALF_EVEN`
   (₹100 over 3 months at 0 %: ₹33.34 the old way, ₹33.33 in v2). Its four tests are therefore not in the ported
   count; `EmiMathTest` (P11) covers v2's engine. This is the one old test class that does not come across, and it
   is not a calc-package test.
7. **Preconditions are rejected, not dereferenced.** The originals would have thrown `NullPointerException` on a
   minimum-due terms field their formula needed, and divided by zero on a configured credit limit of zero. The port
   validates both and throws `IllegalArgumentException`, which `CalcService` turns into a 400 with field errors. No
   computed value changes; the static factories (`MinimumDueTerms.higherOf`, `CustomFee.flat`, …) make the valid
   shapes unmissable.
8. **`/v1/calc/*` is stateless and capped.** POST only, no database, no tenant data, no events, no idempotency key,
   no ETag — every answer is a pure function of the body, so there is nothing to replay or to version. The engines
   are loops over days, cycles and lots and the route class allows 60 requests a minute per account, so
   `CalcService` bounds every list and count (500 transactions, 200 lots, 25 cards, 360 cycles, a 400-day cycle
   window) and the controller caps a body at 64 KB inside the gateway's 256 KB. An admin-console token is refused,
   as on every other user endpoint.

## What the port does not carry over

Stated here rather than left to be discovered (BUILD_README §7 carries these too):

- **`LedgerEngine`'s `dailyLog`.** The original's own port already dropped the per-day audit array: nothing in the
  simulators or the exit-path search reads it, only the total interest and the closing lots. A real gap if a
  day-by-day breakdown is ever wanted for display.
- **Custom fees inside the cycle simulators.** `MinimumPaymentSimulator`, `NoPaymentSimulator` and
  `ExitPathCalculator` simulate a card as if it had no configured custom fees, exactly as the original did, so a card
  that has them costs more in reality than these endpoints report. `CustomFeeCalculator` *is* ported (the original
  had it too) and `FullPaymentCalculator` charges it, so a caller can add the per-cycle fee itself; folding it into
  the simulators would change their ported fixtures and belongs in its own change.
- **The full `calculationTermsSchema`.** `LedgerTerms` carries the rates and the allocation order — what the engine
  reads. Grace-period day counts and the rest of that schema are not modelled.
