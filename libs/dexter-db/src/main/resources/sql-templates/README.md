# SQL templates (MASTER_PLAN 3.2, 3.8, 5.2, 6.3, 6.4, 3.6)

Copied into each service's **first** Flyway migrations with `{{schema}}` replaced by the service name
(`ledger`, `identity`, ...). The role names follow `{{schema}}_owner|_app|_admin|_relay|_readonly`
(created by infra, never by Flyway). Flyway runs as `{{schema}}_owner`.

| File | Contents | Apply order |
|---|---|---|
| `00-base-functions.sql` | schema, grants, `app_account()`, `app_actor_role()` | 1 |
| `10-partitions.sql` | `create_partitions_ahead()`, `drop_partitions_older_than()` (SECURITY DEFINER, relay-callable) | 2 |
| `20-idempotency.sql` | `idempotency_keys` + tenant RLS + relay purge policy | 3 |
| `30-outbox.sql` | partitioned `outbox`, unpublished index, NOTIFY trigger, grants | 4 |
| `40-inbox.sql` | `inbox` (dedupe), grants | 5 |

`in.dexter.db.template.SqlTemplates` renders them (used by tests, and by services' migration
generator notes). `SqlTemplatesIT` applies all of them to Postgres 17 and proves the RLS rules.
