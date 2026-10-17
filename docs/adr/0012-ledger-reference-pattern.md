# ADR-0012: Ledger as the reference service — layers, keyset per filter mask, daily totals in the write transaction

- Status: Accepted (P07). Proof: `ExpenseCrudIT`, `ExpensePlanIT` (1.2M rows), `ExpensesPaginationContractIT`,
  `CreateExpenseIdempotencyContractIT`, `DailyTotalsPropertyIT`, `LedgerRlsIT`, `PlatformMigrationTest`,
  `LedgerArchitectureTest`, api-smoke P07 block, trail-check P07.
- Context: MASTER_PLAN P07 makes the ledger the pattern every later service copies: tenant tables with FORCE RLS,
  keyset paging proven by PlanAssert on a large table, Idempotency-Key on creates, ETag/If-Match on updates, soft
  delete, outbox events per write, and a per-day aggregate that never drifts from the rows.

## Decisions

1. **Layers as identity:** `api` (controllers, body parsing, `Callers.user`) → `app` (use-cases, ports, views) →
   `domain` (pure rules: `CategoryPaths`, `DailyDelta`, `LedgerTime`, `PaymentMethod`, `LedgerErrors`) and `infra`
   (JdbcClient repositories implementing the ports); `config` wires beans. ArchRules enforce it.
2. **Schema `ledger`** (Flyway as `ledger_owner`, app as `ledger_app`): the rendered platform templates
   (`V…1200__platform`, drift-tested) + `V…1201__ledger_core`: `categories` (ltree, 48 system rows with
   deterministic ids, custom rows per account), `expenses`, `expense_daily_totals`, `categorization_rules` (P08).
   Every table ENABLE + FORCE RLS with `app_account()`; the app role has no DELETE on `expenses` (soft delete only).
3. **One keyset query per filter combination.** The list has 8 optional filters; `ExpenseRepository.mask()` turns
   the present ones into a bit mask and `listQuery(mask)` builds (and caches) the base SQL for it, so each
   combination is one static statement with only the predicates it needs (no `OR $x IS NULL` plans). Sort
   `occurred_desc` = `(occurred_at DESC, id DESC)`. Indexes match the plans: `expenses_list` (partial, live rows),
   `expenses_card` and `expenses_merchant` end in `occurred_at DESC, id DESC` so those filters page without a sort;
   the category filter uses GiST `(account_id, category_path)` and sorts (bounded by the account's subtree).
4. **Daily totals are maintained, not computed.** Each write computes `DailyDelta.between(before, after)` (old row
   leaves its IST day × category root, new row enters) and upserts `expense_daily_totals` in the same transaction;
   a bucket that reaches 0 transactions is deleted. A 1 000-operation random property test compares the table with
   a `GROUP BY` over the rows after every batch. The day is the IST calendar day (A-45).
5. **Writes:** create = IdempotencyEngine Mode A (key required, 201 + `Location` + `ETag "0"`); PATCH = JSON merge
   patch with `If-Match` (428 without, 412 stale), `SELECT … FOR UPDATE`, a no-op returns the current row without a
   version bump; DELETE is a soft delete (idempotent 204); `POST /{id}:restore` (AIP-136 custom method). Every
   change bumps `version` (= ETag = event `aggregateVersion`) and appends `ledger.expense.{created,updated,deleted,
   restored}` v1 (`ExpenseChangedV1`: before/after state) to the outbox; the relay publishes to
   `dexter.ledger.expense.v1`.
6. **Categories:** system rows (`account_id NULL`) are visible to every tenant; custom rows only to their account;
   the policy needs a tenant context even for system rows (A-47). Create derives the path from `parentPath` +
   slug(name), ≤ 4 levels, only under a system root; Idempotency-Key optional.
7. **Admin tokens** (`scope=admin`) are refused on user endpoints (`USER_TOKEN_REQUIRED`): admin access to ledger
   data comes later through audited admin use-cases, never by impersonating an account.

## Consequences

- Later services copy: platform migration + drift test, `<svc>_owner`/`<svc>_app`, port/adapter repositories,
  `mask → static keyset query`, PlanAssert on generated data, Idempotency + If-Match contracts, outbox per write.
- P08 adds search, bulk import (`clientRef`), merchants and rules on these tables; P14 consumes the expense events.
