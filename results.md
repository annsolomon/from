# P19 scale proof — results

k6: 393,318 requests, 211 req/s overall, failed rate 6.9463%.

| 8.1 metric | Target | Measured | | How |
|---|---|---|---|---|
| List p99, end-to-end (50/25/24 window) | < 150 ms | 6180.4 ms | **not met** | k6 `http_req_duration{class:list}` p99 |
| List DB time (mean of the keyset query) | < 10 ms | 0.2 ms (max 369.4 ms, 97879 calls) | met | pg_stat_statements, ledger DB |
| Write p99 (idempotency + outbox) | < 200 ms | 6373.9 ms | **not met** | k6 `http_req_duration{class:write}` p99 |
| Recommendation (cached) p99 | < 100 ms | 6072.6 ms | **not met** | k6 `http_req_duration{class:reco}` p99 |
| Recompute after the triggering event p95 | < 2 s | 38.1 ms | met | reco handler p95 (`dexter_event_handle_seconds`), + outbox lag below |
| Ledger throughput, one instance | >= 1,500 req/s mixed | 474 req/s | **not met** | Prometheus: peak 1-minute rate of ledger's HTTP requests |
| Event lag (outbox → published) p95 | < 1 s | 591.7 ms | met | Prometheus: p95 over the run of the ledger relay's oldest-unpublished age (sampled every 5 s) |
| SSE soak (30 min) | streams held; back to 0 after | peak 348, now 0; 4410 streams opened | met | dexter_sse_connections, k6 counter |

## Server-side p99 per service (Prometheus, excl. actuator and the held SSE streams)

| Service | p99 |
|---|---|
| gateway | 30000.0 ms |
| identity | 13493.0 ms |
| ledger | 1356.1 ms |
| reco | 294.2 ms |

## Slowest routes (p99)

| Route | p99 |
|---|---|
| gateway GET UNKNOWN | 30000.0 ms |
| identity POST /v1/auth/login | 13688.2 ms |
| gateway POST UNKNOWN | 9443.8 ms |
| ledger POST /v1/expenses | 1779.9 ms |
| ledger GET /v1/expenses | 1239.0 ms |
| ledger GET /v1/expenses/daily-totals | 1196.4 ms |
| ledger GET /v1/categories | 1164.5 ms |
| identity POST /v1/auth/token/refresh | 339.5 ms |
| reco GET /v1/recommendations | 300.0 ms |
| reco POST /v1/recommendations/stream-ticket | 76.7 ms |

## What the gateway answered (status codes over the run)

| Status | Responses |
|---|---|
| 200 | 317,329 |
| 201 | 49,211 |
| 503 | 24,744 |
| 500 | 1,277 |
| 403 | 699 |
| 504 | 543 |
| UNKNOWN | 9 |

## Connection pool waiters (max during the run)

| Service | Pool | Max pending |
|---|---|---|
| portfolio | HikariPool-1 | 0 |
| catalog | HikariPool-1 | 0 |
| planning | HikariPool-1 | 0 |
| audit | HikariPool-1 | 0 |
| ledger | HikariPool-1 | 497 |
| identity | HikariPool-1 | 118 |
| commerce | HikariPool-1 | 0 |
| billing | HikariPool-1 | 0 |
| reco | HikariPool-1 | 45 |

## Ledger statements by total time (pg_stat_statements)

| Mean ms | Calls | Query |
|---|---|---|
| 0.18 | 68287 | `SELECT id, account_id, occurred_at, amount_paise, currency, merchant_raw, merchant_display, merchant_id, category_path::text AS category_pat` |
| 0.23 | 44626 | `INSERT INTO ledger.expenses (id, account_id, occurred_at, amount_paise, currency, merchant_raw, merchant_display, merchant_id, category_path` |
| 0.06 | 133878 | `INSERT INTO reco.spend_profile (account_id, category_root, window_code, monthly_avg_paise, total_paise, txn_count, projected_version, update` |
| 0.07 | 98545 | `INSERT INTO reco.rec_state (account_id, dirty, dirty_since, computed_at, attempts) VALUES ($1, $4, $5, $2, $6) ON CONFLICT (account_id) DO U` |
| 0.10 | 68287 | `SELECT id, path::text AS path, name, icon, is_system FROM ledger.categories ORDER BY path, is_system DESC` |
| 0.12 | 44626 | `INSERT INTO ledger.outbox (id, topic, message_key, event_type, payload, headers) VALUES ($1, $2, $3, $4, CAST($5 AS jsonb), CAST($6 AS jsonb` |
| 1.27 | 4031 | `SELECT account_id, password_hash, status, mfa_enabled, tz, tier FROM identity.auth_find_login(CAST($1 AS citext))` |
| 0.09 | 44626 | `UPDATE ledger.idempotency_keys SET state = $6, response_status = $1, response_body = CAST($2 AS jsonb), response_headers = CAST($3 AS jsonb)` |
