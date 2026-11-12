# Backup and restore (P19)

## What holds state
| Store | Holds | Backup | Notes |
|---|---|---|---|
| Postgres (one database per service) | everything durable | continuous WAL archive + daily base backup (pgBackRest or the managed service's PITR), plus a daily logical `pg_dump -Fc` per database | the logical dump is what `scripts/restore-drill.sh` proves |
| Vault (Raft) | per-service secrets; **the Transit key that wraps every identity DEK and signing key** | `vault operator raft snapshot save` hourly, encrypted, off-site | **a database backup is unreadable for identity without the Vault snapshot of the same period** |
| Object store (S3) | statements, export zips, audit anchors | bucket versioning + replication | audit anchors let the chain be re-verified after a restore |
| Kafka | events in flight only | none: rebuildable | services are the source of truth; the outbox re-publishes, consumers are idempotent (inbox) |
| Redis | caches, rate-limit buckets, SSE fan-out | none | starts empty; everything rebuilds (cache-aside, versioned keys) |

## Restore one service database (logical dump)
The same commands `scripts/restore-drill.sh` runs for every database in every `verify.sh full`:
```bash
pg_dump  -U postgres -Fc -d ledger -f ledger.dump                 # backup (roles are cluster objects: not in it)
createdb -U postgres -O ledger_owner ledger_restore
pg_restore -U postgres --exit-on-error -d ledger_restore ledger.dump
```
The drill then compares every table's row count, a content hash of the key tables (`ledger.expenses`,
`identity.account_keys`, `audit.audit_log`'s hash chain) and that RLS is still ENABLE + FORCE on the same tables.

Into a new cluster: create the roles first (`infra/postgres/init/00-roles.sql.tmpl`, passwords from Vault), then
restore each database. To swap a restored database in: stop the service (scale to 0), rename
(`ALTER DATABASE ledger RENAME TO ledger_broken; ALTER DATABASE ledger_restore RENAME TO ledger`), start it.

## Point-in-time recovery (the real incident case)
1. Decide the target time (just before the bad deploy / the deletion), from the audit log and the traces.
2. Restore the base backup + WAL to that time into a **new** cluster (never over the live one).
3. Restore the Vault snapshot taken **after** the target time (Transit keys only grow: a later snapshot opens
   everything an earlier database holds; an earlier snapshot may lack a key version the database uses).
4. Verify before switching: the chain check (`POST /v1/admin/audit:verify-chain`, against the anchors in the object store); row counts against the
   dashboards; one sign-in (identity must unwrap its signing key through Transit).
5. Switch the services' connection strings, start them. The outbox re-publishes anything unpublished; consumers
   skip what they already applied (inbox). Downstream services that were *ahead* of the restore point keep data
   the restored service no longer has: reconcile with the privacy/export tooling per account if it matters.

## Account deletion vs. backups (P18)
Deletion destroys the account's DEK (crypto-shred). A restored backup from before the deletion still holds the
wrapped DEK — re-run the privacy saga for every deletion completed after the backup's time
(`audit.privacy_requests` with `status = 'completed'`) before opening the restored system to users.
