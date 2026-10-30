# ADR-0019: Planning — budget spend as deltas, liabilities on the shared EMI engine, copy-on-read scenarios

- Status: Accepted (P13), proven by CI run 41096207559 (git `d03ca6b`): `verify.sh full` PASS 22/22 +
  `trail-check.sh` PASS. Proof: planning unit tests (domain), `SpendProjectionIT`, `SpendPropertyIT`, `BudgetsIT`,
  `LiabilitiesIT`, `ScenariosIT`, `PlanningRlsIT`, `BudgetsPaginationContractIT`, `IdempotencyContractsIT`,
  api-smoke P13, trail-check `p13`.
- Context: MASTER_PLAN P13 asks for budgets with per-period spend kept up to date from ledger events "with a delta
  per matching budget, via the inbox and version guard", thresholds that alert at most once per period, liabilities
  with a schedule, copy-on-read scenarios, and the named tests: the ₹8,884.88 EMI golden, projection equal to a
  recomputed SUM, an expense at 23:30 IST on the 31st landing in the right month, thresholds alerting once, and the
  RLS, pagination and idempotency contracts.

## Decisions

1. **The inbox is the guard for a delta; a per-row version comparison would be wrong.** `budget_period_spend` is
   maintained by applying `−before` and `+after` per matching budget and period. A delta is not idempotent under
   replay, so exactly-once comes from the inbox row written in the same transaction (the mechanism every other
   consumer here uses). The plan's `projected_version` column is kept and records the expense version last applied
   to the row, but it is **not** used to skip events: one period row aggregates many expenses and expense versions
   are per expense, so a version-2 event for one expense would be wrongly skipped after a version-9 event for
   another. Per-account ordering (the envelope keys on the account) plus the inbox is what makes the projection
   exact, and `SpendPropertyIT` is the proof: 600 random creates, edits, deletes and restores, then every stored
   period compared with an independently recomputed sum.
2. **An edit settles both sides.** The event carries `before` and `after`, so a changed amount, day or category is
   one subtract from where it counted and one add to where it counts now — which is why a category change moves
   spend between budgets and a day change moves it between periods without a reconciliation pass.
3. **A budget created mid-period starts at zero, and spend is floored there.** Planning keeps no copy of the
   account's expenses, so it cannot backfill a budget created today with spend recorded yesterday. A later delete of
   one of those earlier expenses would drive the row negative, so the upsert floors at zero — on the inserted row
   too, because Postgres checks a proposed INSERT against the CHECK before falling through to `ON CONFLICT` (the
   P07 lesson, A-47). Stated as a gap in BUILD_README §7 rather than hidden: a backfill needs a read of ledger and
   belongs in its own change.
4. **Only active budgets track.** A paused budget is not tracking, by design, so spend during a pause is not
   counted when it resumes. An ended budget keeps its periods and alerts for the history and is read-only.
5. **Periods repeat from `starts_on`, and the boundary is decided once — in the ledger.** Weekly is every 7 days;
   monthly is the same day each month, clamped in a short month (anchored on the 31st: 31 Jan → 28 Feb → 31 Mar), so
   a budget anchored on the 1st has calendar months; custom is the single `starts_on … ends_on` window. Days are the
   account's local days, and the ledger already stamps every expense with its Asia/Kolkata day
   (`ExpenseStateV1.day`), so an expense at 23:30 IST on the 31st arrives as the 31st and planning does not re-derive
   the boundary from a timestamp.
6. **A threshold alerts at most once per period, enforced by a primary key.** The threshold row
   (`account_id, budget_id, period_start, threshold`) is inserted `ON CONFLICT DO NOTHING` and the event is
   published only when that insert is the one that won, in the same transaction. Spend falling back below a
   threshold and rising again does not re-alert; the next period starts clean. Thresholds are stored sorted and
   distinct so a consumer and the UI see one order.
7. **Liabilities use the shared EMI engine, and their schedule is written once.** `EmiMath` moved into
   `libs/portfolio-calc` (A-74) so planning and portfolio compute an instalment the same way; it reproduces the old
   `budget-service` golden of ₹8,884.88 to the paisa, which is why planning has no EMI of its own (and why ADR-0018
   §6's reasoning — one answer to one question — applies here too). The schedule is what the terms implied on the day
   the liability was recorded and is never rewritten, so a rate change is a new liability rather than a silent
   re-rate. Its **totals are stored with it** (`total_interest_paise`, `total_payable_paise`) rather than
   recomputed as `emi x tenure - principal`: the last instalment is the balancing one, so the product
   overstates the interest by the rounding adjustment (3 paise on the Rs 1,00,000 golden, 661853 not 661856),
   and a total that contradicts the schedule the same API returns is worse than an extra column. A loan carries no
   GST; the GST a credit-card EMI does carry is modelled where those plans live, in portfolio's `emi_plans`.
8. **A scenario is a copy, not a view.** Saving one copies the account's active budgets and liabilities into
   `base_snapshot`; evaluating applies the saved adjustments to that copy. Closing a real liability afterwards does
   not change what the scenario answers. The four adjustments (`setMonthlyIncome`, `setBudgetLimit`,
   `addLiability`, `removeLiability`) are refused rather than ignored when the snapshot cannot satisfy them
   (`SCENARIO_ADJUSTMENT_INVALID`), and a hypothetical loan's instalment comes from the same engine a real one would
   use. `surplusBefore` uses the snapshot's own income, so changing the income does not silently restate the
   starting point the scenario is compared against.
9. **`POST /v1/calc/emi` stays portfolio's (A-42).** Planning calls the EMI library directly; it does not re-serve
   that route, and it makes no HTTP call to portfolio for arithmetic.
10. **The app layer owns JSON.** `base_snapshot` and `adjustments` are `jsonb` read back as text and mapped to
    records in `ScenarioService`; the domain (`ScenarioMath`) never sees Jackson, which is what the architecture
    rules require of a pure domain.

## What this phase does not do

- **No `GET /v1/budgets/category-potential`.** The plan lists a cached read-through to catalog's "potential"
  endpoint. It is the one P13 endpoint not built: it needs a service-to-service call and a cache entry of its own,
  and it answers a catalog question rather than a planning one. Recorded as A-75 and in BUILD_README §7 so the next
  phase can pick it up deliberately.
- **No rollover arithmetic.** `budgets.rollover` is stored and returned, and nothing reads it yet: carrying an
  unspent remainder into the next period changes what every period's limit means, so it belongs with its own
  fixtures rather than as an unexercised branch.
- **No liability payment tracking.** A liability's schedule is projected, not reconciled against payments; nothing
  marks an instalment paid. `LiabilityMath.outstandingAfter` exists for a caller that knows how many have been paid.
