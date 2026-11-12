# P19 scale proof — results against MASTER_PLAN 8.1

**Run:** `scale` workflow run 43309886082 on git `4eef6f3` (2026-11-13), raw data on branch `scale-reports`
(`results.md`, `k6-summary.json`, `k6.txt`, service logs). Setup: one GitHub-hosted runner (4 vCPU, 16 GB) running
**everything** — ten services (full JIT, 768 MB heaps), Postgres 17, Kafka, Redis, Vault, the obs profile, and k6 —
on the **large seed** (100,000 accounts, 5,000,000 expenses, 300,000 user cards, loaded in ~3 min). k6
`perf/k6/p19-scale.js` with `PROFILE=full`: browse (the 50/25/24 window + daily totals + categories, ramping to the
8.1 throughput shape), idempotent writes 100/s with same-key retries and replay checks, cached reco reads 150/s, and
a 30-minute SSE soak with 200 VUs; each VU is its own client (`X-Forwarded-For`), signs in once (jittered) and
refreshes its session every 8 minutes.

## The 8.1 rows

| 8.1 metric | Target | Measured | | Where the time goes |
|---|---|---|---|---|
| List DB time (keyset query, 5M rows) | < 10 ms | **0.18 ms** mean over 68,287 calls | **met** | `pg_stat_statements`: an index range scan whose cost does not depend on table size |
| Recompute after the triggering event | p95 < 2 s | **38 ms** (reco handler p95) | **met** | `dexter_event_handle_seconds{service=reco}` |
| Event lag (outbox → published) | p95 < 1 s | **592 ms** | **met** | p95 of the ledger relay's oldest-unpublished age, under the full write load |
| SSE soak (30 min) | streams held, nothing leaks | 4,410 streams opened, each held 60 s; peak 348 open, **0** a minute after the end | **met** | `dexter_sse_connections` |
| List p99, end-to-end | < 150 ms | 6,180 ms (median 2,770) | **not met** | the host: see below. Ledger's own p99 for the list: 1,239 ms, its queries 0.18 ms |
| Write p99 (idempotency + outbox) | < 200 ms | 6,374 ms (median 3,582) | **not met** | same; 49,211 created (ledger's own p99 for the write: 1,780 ms) |
| Recommendation (cached) p99 | < 100 ms | 6,073 ms (median 2,180) | **not met** | reco's own p99 294 ms; the rest is the gateway and the host |
| Ledger throughput, one instance | ≥ 1,500 req/s mixed | **474 req/s** peak (1-min rate) | **not met** | ledger's Hikari pool had up to 497 waiters: connections held ~20 ms per request for 0.2 ms of SQL — CPU starvation, not the database |

Overall: 393,318 requests, **93 % successful** (317,329 × 200, 49,211 × 201); 24,744 × 503 and 543 × 504 at the
saturated edge; 933,850 iterations dropped by k6 because the box could not take the offered rate.

## Reading it

- **Everything that depends on data size meets its target with a wide margin**: the list query at 5M rows is
  0.18 ms, the write path's statements 0.1–0.2 ms, the recompute 38 ms, the event lag 0.6 s under load. The P07 /
  P14 designs (account-leading keyset indexes, version-guarded projections, outbox + LISTEN/NOTIFY) hold at the
  large seed.
- **The end-to-end latencies measure a saturated 4-vCPU host**, not the services: medians of 2–3.5 s for every
  class, the same for a 0.2 ms query and a cached recommendation, are queueing for CPU. Sign-in (Argon2id, by design
  ~100 ms of CPU each) at 1,871 VUs took p99 13.7 s and starved the other JVMs while sessions were created.
- **Ledger ≥ 1,500 req/s** cannot be shown when ledger shares 4 vCPU with nine other JVMs, Postgres, Kafka and k6.

## Hotspots found and fixed (five runs)

| Run | What it measured | Fix |
|---|---|---|
| r1 43220597023 | the per-IP **auth limit** (10/min): every VU was 127.0.0.1, 164 sign-ins got through | k6 gives each VU its own client IP; services get full JIT + real heaps, 10 % activity sampling |
| r2 43281440436 | 96 % failures: one access token (10-min TTL) per VU for a 30-min run | sessions refreshed every 8 min with the rotating refresh token |
| r3 43289010503 | 1.65M × 503 + 400k × 429: VUs re-trying sign-in every iteration | first sign-in jittered 0–30 s, 10–30 s back-off after a failure |
| r4 43296197146 | still 1.73M × 503 from **open circuit breakers**, cause unlogged | gateway upstream pool sized (1,000, 5 s acquire); every breaker transition logged with its last error |
| r5 43303104577 | the log named it: `BulkheadFullException` — **Spring Cloud CircuitBreaker's default bulkhead of 25 concurrent calls per service**; the 26th concurrent request failed, counted against the breaker, and opened it for everyone | **bulkhead off** (`spring.cloud.circuitbreaker.bulkhead.resilience4j.enabled=false`), `ResilienceIT` sends 60 concurrent calls to one service and requires 60 × 200 |
| r6 43309886082 | the system under real load: 93 % success, the results above | — |

The bulkhead is the one finding that would have hit production: with ~25 concurrent requests per service the
gateway would have started refusing traffic at a fraction of the planned load.

## Plan for the unmet rows

1. **A perf environment that separates the load generator from the system**: k6 on its own machine; ledger on a
   dedicated 4 vCPU node; Postgres on its own node; identity × 3 behind the gateway (Argon2 is meant to scale out);
   the gateway × 2. Same workflow, `BASE_URL` pointing at it.
2. Re-run `scale.yml` there; the expected first limit is ledger's pool (20) at ~20 ms hold time — with the CPU
   available the hold time is the SQL's (~1 ms), i.e. headroom far beyond 1,500 req/s per instance; raise
   `LEDGER_DB_POOL` only if `hikaricp_connections_pending` says so.
3. Keep the targets as alerts: `DexterSlowRequests` (p99 > 200 ms) and `DexterOutboxLag` (> 5 s) already encode
   them in production.
4. Until then the release ships with these numbers recorded and the gap in BUILD_README §7.
