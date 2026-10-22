# ADR-0016: Portfolio A — user cards, billing cycles, auto-match, EMI and the points ledger

- Status: Accepted (P11; proven by CI run 39703341828, git bac4db8).
  Proof: `CardsIT`, `TransactionsIT`, `LinksIT`, `AutoMatchAccuracyIT`, `ProjectionIT`, `EmiIT`, `RewardsReportIT`,
  `PortfolioRlsIT`, `TransactionsPaginationContractIT`, `IdempotencyContractsIT`, `BillingCycleTest`, `EmiMathTest`,
  `MatchScoreTest`, `RewardsDomainTest`, api-smoke P11, trail-check P11.
- Context: MASTER_PLAN P11 asks for the user's cards with statements and transactions, auto-matching of ledger
  expenses to card transactions from `ledger.expense.*` events, an EMI tracker, a rewards points ledger, a savings
  report and a catalog projection fed by `catalog.card.published`.

## Decisions

1. **The catalog arrives as a projection, never as a call.** `catalog_products_proj` holds the published snapshot
   per card product, written only by the `catalog-projection` consumer under `ProjectionVersionGuard` (an older or
   replayed version never overwrites a newer one). It is global data: every tenant reads it, and only the actor
   role `PROJECTOR` — which no token can carry — may insert or update it (RLS). The seed writes the seeded catalog's
   rows with `projected_version 0`, as if consumed, because the seed loads catalog tables without events.
2. **A card is a product + last4 per account; removing is soft.** `user_cards` keeps `status` active / closed /
   removed; removed cards keep their statements, transactions and points, and the same product + last4 can be
   added again (a *partial* unique index `WHERE status <> 'removed'` instead of the plan's plain UNIQUE). Adding
   needs a published product (`UNKNOWN_CARD_PRODUCT`, `PRODUCT_RETIRED`).
3. **Billing cycles are computed, statements materialised on demand.** `BillingCycle.containing(day,
   statementDay, dueOffset)` (IST days; `31` = last day of the month) gives the period of any posting; recording a
   transaction finds or creates that period's statement and moves its debits/credits/total. A closer job ends
   statements whose period is over (`open` → `generated`, minimum due = max(₹100, 5 %) capped at the total) through
   an owner `SECURITY DEFINER` function, because it works across all accounts.
4. **Rewards are earned at record time on the card's catalog terms.** The projection snapshot's rules give the
   rate (most specific category, exclusions, minimum transaction); cashback is valued in paise, points/miles become
   whole points worth `pointValueMilliPaise` each, written to `reward_points_ledger` (`source_ref txn:<id>`, unique,
   expiring after 36 months). The ledger is append-only for the app role (SELECT, INSERT); balance is FIFO by
   expiry (`PointsBalance`), with an expiring-soon horizon of 60 days. Users may redeem or adjust (with a note).
5. **Auto-match is a scored suggestion, never an automatic link.** Candidates: same card, same amount, posting
   day within ±2 days, unlinked debit. Merchant similarity (`MatchScore`) normalises both sides (legal suffixes,
   city/noise words, punctuation), accepts the expense merchant at a word start of the bank descriptor or a
   truncated descriptor that starts the merchant name, and otherwise uses trigram similarity (minimum 0.45). Score
   = 0.75 × merchant + 0.25 × closeness in days; the best candidate becomes one `suggested` link per pair. Confirm
   links the transaction to the expense, rejects rival suggestions and emits `portfolio.link.confirmed`; a deleted
   or materially changed expense rejects its open suggestions and is matched again. Matching runs from both sides:
   a new transaction against projected expenses, a new expense against transactions.
6. **The expense side is a projection of the ledger's events.** `ledger_expenses_proj` (`expense-match` consumer,
   version-guarded) keeps amount, day, card, merchant text and deletion. `ExpenseStateV1` gained the merchant
   display text (A-62); it had no consumer before P11, so the v1 contract changed in place.
7. **EMI is reducing-balance, exact.** `EmiMath` (BigDecimal): EMI = P·r·(1+r)^n / ((1+r)^n − 1) rounded HALF_UP,
   interest per instalment HALF_EVEN on the outstanding, the last instalment takes the remaining principal, GST on
   interest and the processing fee (first instalment). The schedule is stored (`emi_schedule`, keyset by number);
   closing or foreclosing cancels the instalments still due.
8. **Report = rewards − fees − pro-rated annual fee.** Monthly `card_savings_summary` rows are maintained at record
   time; a year report sums them and charges the annual fee in full, a month report charges fee × 1/12.
9. **Source topics are created by the consumer side too.** Topic auto-creation is off; the portfolio relay ensures
   the topics it consumes (`catalog.card` compacted, `ledger.expense`, the retry/DLQ topics) with the producer's
   specs, so a fresh environment does not wait for metadata.

## Consequences

- Statement upload and parsing (P12) feed the same `card_transactions` with `source = statement` and reuse the
  matcher.
- Reco (P14/P15) reads the user's cards and savings summary through events, not through this schema.
- Match quality on real descriptors depends on the ledger's merchant resolver; the accuracy test runs on the seed's
  357 merchants with bank-style descriptors (precision ≥ 0.97, recall ≥ 0.95).
