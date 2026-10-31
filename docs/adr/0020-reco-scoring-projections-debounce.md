# ADR-0020: Reco A — spend as a rebuilt window, the 60/40 blend with its arithmetic exposed, and a debounced recompute

- Status: Accepted (P14). Proof: reco unit tests (`SavingsTest`, `RulesTest`, `ScorerTest`, `ExpenseFitTest`,
  `WindowsAndHashTest`), `SpendProfileIT`, `ProjectionsIT`, `DebounceIT`, `RecommendationsIT`, `RecoRlsIT`,
  `PlatformMigrationTest`, api-smoke P14, trail-check `p14`.
- Context: MASTER_PLAN P14 asks for a spend profile built from ledger events, held-card and catalog projections, a
  per-card savings figure net of the fee after a waiver probability, an expense-fit component, a configurable
  **60/40** blend, explainability (per-category breakdown, caps hit, fee impact, top reasons), a debounced
  recompute (dirty → a `SKIP LOCKED` sweeper claiming accounts quiet for 3s, batch 200) that emits
  `reco.recommendation.updated`, and the named tests: scoring goldens for cap binding and the fee waiver, 50 events
  coalescing into one recompute, projections ignoring stale versions, and freshness correctness.

## Decisions

1. **A rolling window cannot be maintained by deltas, so it is rebuilt.** An expense leaves the 12-month window
   because time passed, not because an event arrived. The deltas therefore land in `spend_month`
   (account, root, month) — exact, append-and-adjust, floored at zero — and `spend_profile`'s three windows are
   recomputed for the touched root from it on every event (A-78). The plan's table keeps its shape and meaning; it
   is simply derived rather than incremented. The average divides by the **months that contributed**, not by the
   window's length, so a profile read on the 2nd of a month is not a twelfth of the truth.
2. **The inbox is the exactly-once guard for spend, not a version compare.** A delta is not idempotent under
   replay, and one `spend_month` row aggregates many expenses whose versions advance independently — so a per-row
   compare would wrongly skip a version-2 event for one expense after a version-9 event for another. Identical
   reasoning to planning's budget spend (ADR-0019 §1); `projected_version` is recorded for diagnosis and is not a
   skip guard. `SpendProfileIT` proves it the only way that counts: 150 random creates and deletes, then every
   stored month and every window compared with an independently recomputed figure.
3. **The held-card and catalog projections *are* version-guarded**, because each row is one aggregate with one
   version sequence. Both go through the library's `ProjectionVersionGuard`, which refuses SQL that does not
   compare `projected_version` — so a projection write cannot silently lose the out-of-order check.
4. **A card's rules are replaced wholesale on a new catalog version**, not merged: a version brings new rule ids,
   so merging would leave the previous version's rules earning alongside the new ones.
5. **`rateBps` is a value rate for points as well as cashback, and that definition is shared, not copied.**
   Portfolio's `RewardTerms` has meant exactly that since P11 (ADR-0014 §5), with a points card's value rounded
   down to whole points. Reco's first draft invented "points per ₹100" instead, which would have made reco's
   rupees disagree with portfolio's for the same card. `RewardTerms` moved into `libs/portfolio-calc` and reco
   earns through it (A-82) — the same remedy as `EmiMath` for planning (A-74), for the same reason: one answer to
   one question. `RulesTest` pins the agreement.
6. **The root/rule depth mismatch is one explicit assumption, in one place.** The profile is per category *root*
   while rules can be written four labels deep, so a rule on `food.dining` is being asked about all of `food`.
   `Rules.share` says what fraction of a root a rule at each depth is credited with (100 % at depth 0-1, 40 %,
   20 %, 10 %), nothing else in the engine guesses, and every explanation reports the `shareBps` it used so a
   reader can see where a figure came from. These are assumptions, not measurements: P15's optimizer has the
   per-category detail and supersedes them (A-81).
7. **The fee waiver is a ramp, not a step.** A step at the threshold would flip a recommendation on one rupee of
   spend. The probability is 0 at half the threshold or below, 100 % at or above it, and linear between; the fee is
   counted at its expected value. A milestone is counted **only** when the account's spend reaches it, because
   crediting an unreachable benefit flatters an expensive card.
8. **Savings are normalized against the best card in the candidate set**, not an absolute ceiling, because what a
   reader wants to know is how much better the best option is than this one. A set in which every card loses money
   normalizes to zero across the board rather than flattering the least bad one, and a card with negative savings
   is never "recommended" whatever its fit.
9. **A catalog change does not fan a dirty flag out to every account.** One republished card would otherwise
   enqueue a recompute for the whole user base for a change most accounts cannot see. Instead the read path
   computes the current state hash from three small per-account queries and recomputes that one account when it
   differs (A-83). So `GET /v1/recommendations` is cached and always current, and `rec_state.dirty` is set only by
   the per-account streams (ledger, portfolio).
10. **The state hash is of the inputs, including the engine's own version.** Hashing the output would hide a change
    in the arithmetic itself, so `StateHash.ENGINE_VERSION` is part of it and must be bumped whenever the scoring
    changes — otherwise stale stored results would be served as fresh. Equal hash means equal inputs, which is what
    lets a recompute clear `dirty` without writing a result or publishing an event: 50 expense events in a burst
    become one recompute and at most one `reco.recommendation.updated`.
11. **The sweeper claims and clears in one statement.** `claimDirty` selects the oldest dirty accounts
    `FOR UPDATE SKIP LOCKED` and clears `dirty` in the same UPDATE, so a second sweeper cannot pick up an account
    the first is still recomputing, and every instance can sweep at once without leader election. A recompute that
    throws re-dirties the account with `attempts` raised, so a poisoned account is retried rather than lost.
12. **`recommendation_history`'s partitions are the relay's job.** `create_partitions_ahead` is SECURITY DEFINER
    and granted to `reco_relay` alone, so the app pool cannot reach it; the relay's maintenance pass keeps two
    months ahead and drops past the 180-day retention. Granting the app role EXECUTE instead would have let any
    app transaction create partitions on any table.
13. **`window` is a reserved word in Postgres**, so the column is `window_code`; the API field stays `window`, so
    the plan's vocabulary survives where it is read (A-80).

## What this phase does not do

- **No wallet optimizer, best-card-now or SSE.** Those are P15 by the plan's own split, and §6's share assumption
  is where the optimizer will improve on this phase rather than replace it.
- **No merchant-level rules in the score.** A rule can carry a `merchantId`, and the projection stores it, but the
  spend profile has no merchant dimension — so a merchant rule is scored as the category rule it sits under. The
  data to do better is in the ledger's own projection and belongs with best-card-now, which is asked about one
  merchant at a time.
- **No per-transaction cap conversion.** A `txn`-period cap cannot become a monthly ceiling without a transaction
  count the profile does not carry, so it is left unapplied and every affected line reports
  `capNotConvertible: true` rather than inventing a number.
- **No category-potential read-through** (A-75, inherited from P13): still unbuilt, and still a catalog question.
