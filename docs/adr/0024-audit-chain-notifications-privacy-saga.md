# ADR-0024: Audit — a hash-chained log, notifications with backoff, and a resumable privacy saga

- Status: Proposed — proven when `verify.sh full` and `trail-check.sh` pass on CI (BUILD_README §3).
- Context: MASTER_PLAN P18 asks for an append-only audit log (monthly partitions, BRIN, REVOKE UPDATE/DELETE, a
  single-writer hash chain under an advisory lock, a daily anchor hash exported to object storage), activity events
  (daily partitions, 90 days) with an hourly aggregate for an admin dashboard, notifications (email / push / in-app,
  a keyset inbox, preferences) fed by security alerts, budget thresholds, subscription changes and application
  status updates, and privacy requests: an export (each service's JSON part, zipped, a presigned link for 24 h) and a
  delete saga (identity freezes, every service erases or anonymizes and acknowledges, identity destroys the DEK,
  audit keeps a pseudonymous record; timeouts, retries, resumable). Named tests: a tampered row breaks chain
  verification; the saga completes across all services and each holds no rows for the account afterwards;
  notification retry with backoff.

## Decisions

1. **One writer, one line.** Every entry goes through `audit.append(...)`, a SECURITY DEFINER function that takes
   `pg_advisory_xact_lock(hashtext('audit.chain'))`, locks the one-row `chain_head`, numbers the entry `seq + 1`
   and stores `hash = SHA-256(prev_hash || canonical row)`. Nobody holds INSERT, UPDATE or DELETE on `audit_log`
   (the app and admin pools call the function), and a trigger refuses UPDATE / DELETE even for the owner. `seq` is
   gap-free; `occurred_at` never goes backwards along it (it is the append time; an event's own time is in
   `details.eventAt`), so the monthly partitions never need a late row.
2. **The canonical row is SQL's.** `audit.chain_hash(prev, seq, occurred_at, id, actor_type, actor_id, action,
   target_type, target_id, ip_hash, request_id, details)` renders the row with `format('%s|%s|%s|%L|…')` — `%L`
   keeps NULL and `''` apart, the timestamp is exact epoch microseconds, `details` is jsonb's canonical text — and
   both the writer and the verifier call it, so there is one definition of "the row" and no Java/SQL drift.
3. **Verification walks to a head read first.** `:verify-chain` reads the head, then the rows in seq batches of
   2,000 up to that seq (rows appended meanwhile wait for the next run), and checks each row's link, each row's
   recomputed hash, the head, and every anchor against its row *and* against its exported copy. A row edited in
   place breaks at that row; an edited row whose hash was recomputed breaks at the next row's link; a rewritten
   tail (every row re-chained, the head moved) passes every link and is caught by the last anchor before it. The
   run is itself an entry (`audit.chain.verified`).
4. **Anchors are written by a job, exported first.** The anchor job (every `anchor-every`: 24 h in production, a
   minute in dev) stores the head as `audit_anchors(seq, hash)` after putting `anchors/<seq>.json` in the audit
   bucket — a stored anchor always has its copy. Keyed by seq, not by day, so the dev cadence needs no special
   case and a quiet day writes nothing.
5. **Notifications: one row per (event, channel).** The rules (`NotificationRules`): every notification goes
   in-app; email and push follow the account's switches; a muted category gets nothing — except security, which
   cannot be muted and always goes in-app and by email. In-app rows are delivered on insert (the inbox); email and
   push are `pending` until the worker claims them (`FOR UPDATE SKIP LOCKED`, leased 60 s), sends them outside any
   transaction, and records the outcome: sent, or retried at `base · 2^(n−1)` (capped), or `failed` after the last
   attempt. `UNIQUE (source_event_id, channel)` makes a redelivered event a no-op. The transport is a mock in dev
   and tests (`MockNotificationSender`; dev refuses each notification's first attempt so the retry path runs on
   every stack); the audit service holds no email address (identity does), so a real provider is P19's (§7).
6. **The saga's state is one row.** `privacy_requests` holds the kind, the phase (delete: freeze → erase → shred →
   local → done; export: export → archive → done), the attempt and every service's answer per phase (`steps`). A
   remote phase is asked with `audit.privacy.requested` (outbox, same transaction as the state change) naming the
   services that have not answered; each answers `<svc>.privacy.completed` on its own topic; the acknowledgement
   consumer records it and, when every service of the phase is done, enters the next phase in the same
   transaction. A job (every 2 s, `SKIP LOCKED`) re-asks the silent services when a phase times out, fails the
   request after `max-attempts`, and runs the local phases. Everything is in the row, so a restart resumes where it
   stopped, and an operator can `:resume` a failed request (asking only the services that have not answered).
7. **Each service's part is SQL it owns, run by a shared library.** Every participating service has
   `<svc>.privacy_erase / _export / _remaining` (identity also `_freeze` / `_shred`), SECURITY DEFINER, executed by
   its app role. The functions *find* the account tables (every table of the schema with `account_id`), so a table
   added later is covered without touching them — and they refuse to run if the owner cannot see such a table under
   FORCE RLS (a missing owner policy would make "0 left" a lie). Erase re-keys the anonymized tables to one fresh
   random id (billing's subscriptions, payments, invoices, invoice lines and webhook events — financial records are
   kept, unlinked, and a live subscription is ended first; commerce's conversions) and deletes the rest, finding a
   foreign-key-safe order by retrying refused deletes. Export returns `{table: [rows]}` with `*_enc`, `*_hash` and
   secret-named columns removed. `libs/dexter-privacy` is the participant: its consumer `privacy-<svc>` on
   `dexter.audit.privacy.v1`, the phase function, `privacy_remaining`, the part written to the audit bucket and the
   acknowledgement appended to the service's outbox — all in one transaction, inbox-deduplicated; it creates its
   own topics, so no service's relay configuration knows about privacy. Off unless `dexter.privacy.enabled`
   (`run.sh` sets it; never in the Maven ITs).
8. **What a deletion leaves.** identity: a tombstone (`status deleted`, `<id>@deleted.invalid`, no phone, name or
   usable password) so the id is never reused, and the account's data key destroyed (`dek_enc` emptied,
   `destroyed_at` set) — every ciphertext sealed under it, in any copy, is unreadable. Every other service: no row
   with the account id (`privacy_remaining = 0`). audit: its own rows erased, the account's requests pseudonymized
   (`account_id` NULL, `subject_ref` = HMAC-SHA-256 of the id under a server key), and a `privacy.delete.completed`
   entry naming only the pseudonym. Earlier log entries keep the account's id: the log is append-only by design and
   the id alone now resolves to nothing (identity's row is a tombstone), which is what pseudonymous means here.
9. **Freeze before erase, shred last.** identity freezes first (deletion_pending: sign-in and refresh refuse
   anything but `active`; every session revoked with reason `account_deletion`), so no new data arrives while the
   services erase; the DEK goes only after every service has answered, so a failed erase can be retried with the
   account still intact in identity.
10. **Export archive.** When every part is in, the job zips `<svc>.json` for each service, `audit.json` (the
    inbox, preferences, activity) and a `manifest.json`, stores `privacy/<id>/export.zip`, and the request's view
    presigns a GET valid until 24 h after completion (then `downloadUrl` is null). An in-app notification says it
    is ready.
11. **Activity.** One row per sampled gateway request, keyed by the event id (`ON CONFLICT DO NOTHING`, so a
    redelivery adds nothing — no inbox row per request) and added to its hour in the same transaction; the
    dashboard reads only `activity_hourly`. Daily partitions are dropped after 90 days by the relay leader; the
    log's monthly partitions are kept.

## Consequences

- The audit service is the only consumer of seven new topics (`dexter.<svc>.privacy.v1`) and the producer of one
  (`dexter.audit.privacy.v1`); a participant that is down only delays its phase (re-asked on timeout).
- A test of the whole saga runs in the audit module: seven databases migrated by their services' own migration
  files, the library code that the services run, and audit on Kafka (`PrivacySagaAcrossServicesIT`).
- Objects a service stored elsewhere (portfolio's uploaded statements in the object store) are not yet erased by
  the saga (§7).
