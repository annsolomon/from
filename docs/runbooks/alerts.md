# Alerts runbook (P19, ADR-0025)

Rules: `infra/prometheus/alerts.yml` (checked by `promtool` in `verify.sh static`). Dashboards: Grafana folder
**Dexter** — *Services (RED)*, *Events*, *Resources*. Traces: Grafana → Explore → Tempo; every event carries the
`traceparent` of the request that wrote it, so a consumer's span sits in the same trace as the request.

Start every alert the same way: open the dashboard named below, narrow `service`, then open a slow or failed trace
from Tempo (`{ resource.service.name = "<svc>" && status = error }`).

## DexterServiceDown
`up{job="dexter-services"} == 0` for 1 m — Prometheus cannot scrape `/actuator/prometheus`.
1. `scripts/run.sh status` (or the orchestrator's pod status). Liveness/readiness: `/actuator/health/{liveness,readiness}`.
2. Logs of the instance (`.run/logs/<svc>.log` locally). Typical causes: Flyway migration failed at start
   (fix the migration, never edit an applied one), database or Kafka unreachable, out of memory.
3. If one instance of several is down the others carry the load; restart it. If all are down, see
   [deploy.md](deploy.md#rollback) for rollback.

## DexterHighErrorRate
More than 2 % of a service's requests answer 5xx for 5 m. *Services (RED)* → "5xx ratio", "Slowest routes".
1. Tempo: `{ resource.service.name = "<svc>" && status = error }` → the failing span shows the exception.
2. A 5xx from the gateway with healthy services behind it is the circuit breaker or a timeout: check the
   downstream's latency first.
3. Database errors: *Resources* → "DB connections pending"; see DexterDbPoolExhausted.
4. A bad deploy: roll back ([deploy.md](deploy.md#rollback)).

## DexterSlowRequests
p99 above 200 ms for 10 m (targets, MASTER_PLAN 8.1: reads 150 ms, writes 200 ms, reco cached 100 ms).
1. *Services (RED)* → "Slowest routes (p99, top 10)" names the route.
2. Tempo: the slow trace shows whether the time is in SQL (JDBC spans), another service, or the app.
3. SQL: `EXPLAIN (ANALYZE, BUFFERS)` the statement as the `<svc>_app` role with `app.account_id` set (RLS
   changes plans); the Query → Index tables in `docs/api/routes.md` name the index each query expects.
4. Pool wait shows as "DB connection acquire": raise the pool only after the queries are right.

## DexterOutboxLag
The oldest unpublished outbox row of a schema is older than 5 s for 2 m (target: p95 < 1 s).
1. *Events* → "Outbox lag", "Outbox pending". One schema or all?
2. All: Kafka is down or slow — the relays keep the rows and catch up on their own when it returns (P19 chaos
   test: Kafka down 60 s). Check the broker.
3. One schema: that service's relay leader. The leader holds the advisory lock; if its instance is stuck, kill it
   — another instance takes the lock within the lease (P19 chaos test: relay leader kill).
4. One row never publishes while others do: the broker rejects it (e.g. too large); the relay log names the row
   and the error.

## DexterDeadLetters
A consumer's DLQ is not empty. → [dlq-replay.md](dlq-replay.md).

## DexterConsumerLag
A Kafka consumer is more than 1000 records behind for 5 m. *Events* → "Consumer lag", "Handler p99".
1. Handler slow (p99 up): trace it like DexterSlowRequests.
2. Handler failing: retries pile up on `<topic>.<consumer>.retry-*`; the log has the error, the DLQ fills next.
3. Not enough instances: a topic's partition count caps the parallelism of one consumer group.

## DexterDbPoolExhausted
Requests wait for a database connection for 2 m. *Resources* → "DB connections active / pending".
1. Long transactions: `SELECT pid, now() - xact_start, state, query FROM pg_stat_activity WHERE datname = '<svc>'
   ORDER BY 2 DESC` (as `<svc>_admin`).
2. Locks: `pg_locks` joined to `pg_stat_activity` — idempotency keys wait up to `lock_timeout` (2 s) by design.
3. After a Postgres restart pools recover by themselves (P19 chaos test); if not, restart the instance.

## DexterHeapPressure
A heap pool above 90 % for 10 m. *Resources* → "Heap used", "GC pause". Take a heap histogram
(`jcmd <pid> GC.class_histogram | head -30`) before restarting; SSE streams (`dexter_sse_connections`) and
caches are the usual growers.
