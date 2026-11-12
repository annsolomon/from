# Partition maintenance (P19)

Partitioned tables are created ahead and dropped by age **by the services themselves**; this runbook is for when
that machinery reports a problem. Functions (per service schema, SECURITY DEFINER, `V…__platform.sql`):
`<svc>.create_partitions_ahead(table, grain, ahead)` and `<svc>.drop_partitions_older_than(table, cutoff, column)`;
names `<table>_pYYYYMMDD` (day) or `<table>_pYYYYMM` (month), bounds in UTC.

| Table | Grain | Kept | Who maintains |
|---|---|---|---|
| `<svc>.outbox` (the nine services with events) | day, 3 ahead | until published + 3 days | the relay leader, hourly |
| `audit.audit_log` | month, 3 ahead | forever (hash chain) | the audit relay's maintenance tasks |
| `audit.activity_events` | day | 90 days | the audit relay's maintenance tasks |
| `commerce.deal_clicks`, `commerce.ad_impressions_raw` | day, 3 ahead | per ADR-0023 | the commerce relay's maintenance tasks |
| `reco.recommendation_history` | month, 2 ahead | per ADR-0020 | the reco relay's maintenance tasks |

The other tables are not partitioned; `ledger.expenses` and `portfolio.card_transactions` have a playbook for when
they must be (partitioning-playbook.md).

## Signals
- Log `outbox relay <svc>: partition for <date> missing` (ERROR) and `OutboxRelay.partitionsHealthy() == false`:
  tomorrow+1's partition does not exist — inserts into the outbox will fail at midnight UTC.
- An insert failing with `no partition of relation "<table>" found for row`.
- Disk growth on a table that should be bounded (a drop that stopped working).

## Fix
1. Is there a relay leader? `dexter_outbox_leader{schema="<svc>"}` = 1 on exactly one instance (Grafana Explore).
   No leader → the service's instances are down or cannot reach Postgres; fix that first (alerts.md).
2. Create the missing partitions by hand, as the relay role does:
   ```sql
   -- as <svc>_relay (or <svc>_owner)
   SELECT <svc>.create_partitions_ahead('outbox', 'day', 3);
   SELECT <svc>.create_partitions_ahead('audit_log', 'month', 3);   -- audit only
   ```
3. Drops: `SELECT <svc>.drop_partitions_older_than('outbox', now() - interval '3 days', 'published_at');` drops
   only partitions whose rows are all published; an unpublished row older than the cutoff keeps its partition (and
   shows up as outbox lag — see alerts.md).
4. Never `DROP TABLE` an `audit_log` partition: the chain verification (`audit.verify`) needs every row.
5. A default partition is deliberately absent: a row with no partition fails loudly instead of landing somewhere it
   will never be dropped from.
