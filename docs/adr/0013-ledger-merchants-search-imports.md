# ADR-0013: Ledger B — merchant resolver, bounded search, bulk and CSV import

- Status: Accepted (P08). Proof: `ResolverAccuracyIT`, `MerchantApiIT`, `ExpenseSearchIT`, `ExpensePlanIT`
  (search), `BulkExpensesIT`, `ImportIT`, `InsightIT`, `LedgerRlsIT`, `MerchantTextTest`, `SigV4Test`,
  api-smoke P08 block, trail-check P08.
- Context: MASTER_PLAN P08 adds the merchant directory and resolver, text search, bulk create, CSV import through a
  presigned upload, the calendar, summaries and user rules on top of the P07 reference service.

## Decisions

1. **Merchants are global reference data in the ledger schema.** `merchants` + `merchant_aliases` have no account
   column; RLS lets the app role read active rows and nothing else (no write grant); the seed (and P10 later) writes
   as the owner. Aliases carry a generated `alias_compact` (spaces removed): statement descriptors drop spaces and
   cut names at 20 characters.
2. **Resolver pipeline** (`MerchantText` → `MerchantResolver`): descriptor shapes (UPI/NB/wallet/POS/`NAME*ref`),
   VPA handles and long reference numbers removed; text variants from most to least specific (full, without a city
   suffix, without a company form, without a channel word — "air india" stays a name); exact alias on the variants,
   then on their compact forms, then the prefix of a truncated descriptor; trigram similarity on the compact form
   with `pg_trgm.similarity_threshold` set per transaction — accepted at 0.45 (the plan's `set_limit`); below it, a
   one/two-edit typo stage (optimal string alignment) over the same candidates. Confidence: exact 900–1000,
   compact 880–980, prefix 800, trigram 450–850, typo 500/600, none 0. User rules (contains / merchant id, priority
   order) run after the merchant is known and decide the category (source `rule`, confidence 1000).
3. **The cache sits outside transactions.** dexter-cache refuses to run inside one (a loader joining a write
   transaction could cache uncommitted data), so every write path first `prefetch`es the alias stages through Redis
   (global scope `merchant-alias`, 1 h; misses cached too), each miss loading in its own read transaction, and then
   resolves inside the write transaction from that map (rules are read there). Autocomplete answers are cached for
   10 min (`merchant-ac`).
4. **Search is bounded by the tenant + date btree.** `GET /v1/expenses/search` always has a window (default the last
   12 months, at most 366 days) and the same keyset as the list; the text is a recheck on the rows of that range
   (ILIKE on display/raw merchant and note, pg_trgm word similarity on the merchant). No trigram index on
   `expenses`: it would index every tenant's text, and the per-tenant date range is what bounds the work (A-48).
5. **Bulk = one transaction, per-item outcome.** Field errors are found before the transaction; unknown categories
   and duplicate `clientRef`s inside it. Rows go in with multi-row `INSERT … ON CONFLICT (account_id, client_ref)
   WHERE client_ref IS NOT NULL DO NOTHING RETURNING id`; existing references are reported as `duplicate` with the
   existing id; daily totals get one change per bucket; events go through `OutboxWriter.appendAll` (one multi-row
   insert per 500 events). Idempotency-Key required: a retry replays the same per-item answer.
6. **CSV import is a job with a presigned upload.** `POST /v1/imports` returns a SigV4-presigned PUT URL (path
   style, host-only signature, `UNSIGNED-PAYLOAD`: the browser uploads directly to the object store); `:start`
   queues it; a worker claims jobs with a SECURITY DEFINER function (`FOR UPDATE SKIP LOCKED`, stale running jobs
   re-claimed after 10 min, at most 3 attempts) and runs them as the job's tenant. Staging: `COPY … FROM STDIN
   (FORMAT csv, HEADER MATCH)` into a transaction-scoped temporary table (COPY FROM cannot target an RLS table),
   read back, validated (`ImportRow`), merchants resolved, then merged in a second transaction with the bulk
   machinery. Rows without `client_ref` get a content hash + occurrence number as their reference, so importing the
   same file again inserts nothing and two identical rows in one file both count.
7. **Calendar and summary read the aggregate.** Day and category views come from `expense_daily_totals`; merchant
   and payment-method views group the range's live expenses. Upcoming liabilities come through a `LiabilitySource`
   port, empty until portfolio (P11) and planning (P13) publish them (A-49).

## Consequences

- P10 (catalog ingestion) or a later merchant pipeline writes merchants as the owner and must bump the
  `merchant-alias` / `merchant-ac` cache scopes.
- P12 renders S3 identities for SeaweedFS; the SigV4 signer then carries real keys (no code change) and moves to a
  shared library when a second service needs it.
- P14 (reco) consumes the bulk/import `ledger.expense.created` events like single creates.
