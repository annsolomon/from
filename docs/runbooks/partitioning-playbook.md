# Partitioning playbook: `ledger.expenses` and `portfolio.card_transactions` (> 500M rows)

**Status: documented, not executed** (MASTER_PLAN P19 step 8). Today both tables are plain heap tables with
account-leading keyset indexes; at the large seed (5M expenses / 100k accounts) the list query is an index range scan
whose cost does not depend on table size (docs/perf/results.md). Partitioning becomes worth its cost when vacuum,
index rebuilds and backups of one table become the bottleneck — the trigger below.

## Trigger
Any of: > 500M rows; autovacuum on the table running > 6 h; `expenses_list` index > 25 % of RAM; logical backup of
the ledger database > 2 h.

## Design
- **Key: hash of `account_id`, 32 partitions** (`PARTITION BY HASH (account_id)`), not time. Every hot query is
  per account (RLS + the keyset indexes all lead with `account_id`), so each query touches exactly one partition;
  time partitioning would make "my last 50 expenses" scan up to 12 monthly partitions and gives no retention benefit
  (expenses are kept).
- The primary key must contain the partition key: `PRIMARY KEY (account_id, id)`. `id` stays globally unique by
  construction (UUIDv7) — keep a `UNIQUE (id)`-equivalent check out: lookups by id already carry the account (RLS).
- Indexes, RLS policies, grants and triggers are declared on the parent; Postgres creates them per partition.
- Foreign keys *to* `expenses` (none today) would have to include `account_id`.
- `card_transactions`: the same, `PARTITION BY HASH (account_id)`, PK `(account_id, id)`; its FK to `user_cards`
  stays.

## Migration (online, no downtime)
1. **Expand** (release N): create `ledger.expenses_p` (partitioned, 32 partitions, same columns, indexes, RLS, grants).
   Add a trigger on `expenses` that mirrors every INSERT/UPDATE/DELETE into `expenses_p` (same transaction).
2. **Backfill** in batches by account (`INSERT … SELECT … WHERE account_id BETWEEN … ON CONFLICT DO NOTHING`), 10k
   rows per transaction, throttled to keep replication lag < 1 s. Verify per batch: counts + md5 per account
   (the restore drill's hash query, per account range).
3. **Switch** (release N+1, a short lock): `BEGIN; LOCK TABLE ledger.expenses IN ACCESS EXCLUSIVE MODE;` final
   check (counts equal), `ALTER TABLE expenses RENAME TO expenses_old; ALTER TABLE expenses_p RENAME TO expenses;`
   move the outbox / daily-totals triggers to the new table; `COMMIT`. The lock is held for seconds: no data moves.
4. **Contract** (release N+2, after a week): drop the mirror trigger and `expenses_old`.
5. Rehearse 1–4 on a restored copy of production first (restore.md) and keep the timings in this file.

## Checks after the switch
- `EXPLAIN` the list query as `ledger_app` with an account set: exactly one partition in the plan.
- PlanAssert tests (`ExpensesPlanIT`-style) run against the partitioned table in CI.
- RLS: `rls_check.py` accepts partitioned parents (ENABLE + FORCE on the parent applies to all partitions).
