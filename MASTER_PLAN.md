# Dexter v2 — Master Plan (Enterprise Rebuild)

> **Read this entire file before writing any code.** It is the single source of truth for the
> rebuild. Every chat implements exactly one phase (P00–P19), verifies the previous one, and
> hands off through `BUILD_README.md`.
>
> This file is **static**. Do not edit it during phase chats. If the plan has to change, record
> the change as an amendment in `BUILD_README.md → Plan Amendments`, including the reason.
> Later phases follow those amendments.

---

## 0. How to use this plan (the chat protocol)

### 0.1 What Solomon uploads into each new chat

| # | File | Purpose |
|---|------|---------|
| 1 | `dexter-enterprise-java-updated.zip` | **Old repo.** Reference only. Port verified *logic* from it; never copy its architecture. |
| 2 | `dexter-v2-latest.zip` | **New repo**, as the previous chat left it. Not uploaded in the P00 chat. |
| 3 | `MASTER_PLAN.md` | This file. Unchanged. |
| 4 | `BUILD_README.md` | Living status file. A copy also lives inside the new repo. |
| 5 | `verify-report.md` | Output of `./scripts/verify.sh full`, run in Codespaces after the previous chat. Not uploaded in the P00 chat. |

### 0.2 Opening prompt — paste this at the start of every chat

```
You are continuing the Dexter v2 enterprise rebuild.
Read MASTER_PLAN.md completely, then BUILD_README.md, then verify-report.md.
Unzip dexter-v2-latest.zip to /home/claude/dexter-v2 (the old repo zip is reference only).

This chat = Phase P<NN> — <name>.

Follow the Chat Protocol (§0.3) exactly:
1. Verify the previous phase against its Definition of Done and verify-report.md.
   List every issue, then fix it before starting new work.
2. Implement P<NN> fully: code, SQL, indexes, RLS, tests, and the trail page.
3. Self-review against §0.5 and the phase checklist.
4. Update BUILD_README.md (root copy + repo copy).
5. Zip the whole repo to /mnt/user-data/outputs/dexter-v2-P<NN>-r<response#>.zip and present it
   together with BUILD_README.md.
6. End with the exact Codespaces commands I must run and what to paste back.

Rules: every response ships an updated zip + BUILD_README.md. Never claim something compiled or
passed unless verify-report.md proves it.
```

### 0.3 Chat protocol (the AI must follow this)

1. **Load context.**
   - Read MASTER_PLAN.md, BUILD_README.md and verify-report.md.
   - Unzip the new repo.
   - Read the files the current phase touches. **Never guess an API that exists in the repo — open it.**
2. **Verify the previous phase.**
   - Walk its DoD checklist item by item. Use verify-report.md for anything that needs execution.
   - Review the code against the Global Rules (§3–§9).
   - Write findings into BUILD_README → "Verification of P<NN-1>".
   - Fix every issue first. If the fix is large, it becomes the first work item of this phase.
3. **Implement the current phase** in the order its "Steps" list gives.
4. **Self-review.**
   - Run whatever works in the sandbox: `./scripts/verify.sh static`, plus `unit` if Maven is reachable (see §0.6).
   - Walk the phase DoD.
   - Grep for forbidden patterns (§3.10).
5. **Update BUILD_README.md** (template in Appendix A):
   - status table, phase log, decisions (ADRs), deviations, known gaps;
   - "Next chat starts with";
   - the exact verify commands.
6. **Ship.**
   - `cd /home/claude && zip -r /mnt/user-data/outputs/dexter-v2-P<NN>-r<k>.zip dexter-v2 -x '*/target/*' '*/node_modules/*' '*/.git/*'`
   - Copy BUILD_README.md to outputs.
   - Call `present_files`.
7. If a phase needs several responses, **every response still ships a zip + README**, and the
   README's "In-progress" section says exactly where work stopped.

### 0.4 Status vocabulary — use these exact labels

| Label | Meaning |
|-------|---------|
| `WRITTEN` | Code exists and has not been compiled. |
| `COMPILED` | `mvn -q -DskipTests verify` passed. Proof: a report. |
| `UNIT-GREEN` | Unit tests pass (no Docker needed). Proof: a report. |
| `IT-GREEN` | Testcontainers integration and API tests pass in Codespaces. Proof: verify-report.md. |
| `DONE` | IT-GREEN, plus the DoD checklist is fully ticked, plus the trail page was exercised. |

A phase may not be marked `DONE` without `verify-report.md` evidence.

### 0.5 Self-review checklist (every response)

- [ ] No JPA/Hibernate/Spring Data imports anywhere. JdbcClient only.
- [ ] Every tenant table has ENABLE + FORCE RLS, a policy, and an RLS proof test.
- [ ] Every list endpoint uses the shared keyset pager. No `OFFSET`, no `COUNT(*)` for paging.
- [ ] Every query has a supporting index, listed in the phase's Query→Index table.
- [ ] Money is `long` paise (DB `BIGINT`); rate math uses `BigDecimal`. No `double`/`float` for money.
- [ ] Every mutating POST/PATCH/PUT/DELETE supports `Idempotency-Key` (required where the phase says so).
- [ ] Every state change that other services care about is written to the outbox in the same transaction.
- [ ] All consumers dedupe through the inbox.
- [ ] Input validated, errors returned as ProblemDetail with a stable `code`.
- [ ] Nothing in `/test-ui` is referenced by any service.
- [ ] BUILD_README updated. Zip shipped.

### 0.6 Environment constraints (important)

- **The claude.ai sandbox has no Docker**, so Testcontainers integration tests run only in Codespaces.
- **Maven Central is blocked by default in the sandbox.** Solomon can add `repo.maven.apache.org` and
  `repo1.maven.org` to Claude's network allow-list (Settings → network/code execution). With that
  enabled the AI can run `./scripts/verify.sh unit` itself. Without it, the AI can only run `static`.
- **Codespaces is the source of truth.** `./scripts/verify.sh full` writes `verify-report.md`,
  which Solomon uploads into the next chat.

---

## 1. Goals and non-goals

**Goals**
- Production-grade Indian credit-card intelligence and personal-finance backend, as Java microservices.
- **Raw SQL via JdbcClient only.** Flyway migrations. Postgres RLS on every tenant table.
- Scales to **millions of accounts and hundreds of millions of expense rows** without redesign.
- Every endpoint is safe to retry (idempotency).
- Every event is delivered at-least-once and processed exactly once (outbox + inbox).
- Defense in depth: authentication, authorization and RLS together, plus audit, rate limits,
  encryption, and DPDP-Act-aware privacy handling.
- Each phase is independently testable. A throwaway HTML trail visualises the logic and is
  **deleted in P19**.
- Every feature is improved over the old repo (see §12).

**Non-goals**
- Storing card numbers. Dexter never stores a full PAN or CVV — only `last4` and the network — so
  PCI-DSS stays out of scope.
- Moving money. Dexter is not a payment aggregator. Billing uses a provider adapter; P16 builds a mock one.
- KMM mobile. It is a separate track and consumes these APIs later.

---

## 2. Architecture

### 2.1 Services (10 deployables + 1 Python)

| Service | Owns | Database | Replaces (old) |
|---|---|---|---|
| `gateway` | Edge: routing, JWT pre-check, rate limit, request-id, header hygiene | — (Redis) | gateway-service |
| `identity` | Accounts, credentials, sessions, refresh tokens, MFA, JWKS, admins, RBAC | `identity` | auth-service, admin-monolith (auth part) |
| `ledger` | Expenses, categories (ltree), merchants (pg_trgm), daily totals, calendar, imports | `ledger` | expense-service |
| `catalog` | Issuers, card products, versioned reward rules, offers, ingestion pipeline | `catalog` | catalog-service, recalc-worker (catalog part) |
| `portfolio` | User cards, statements, card transactions, EMI tracker, reward-points ledger, calc engines | `portfolio` | card-portfolio-service |
| `planning` | Budgets, budget spend projections, liabilities, sandbox scenarios, alerts | `planning` | budget-service |
| `reco` | Spend profile, catalog projection, scoring, wallet optimizer, best-card, SSE | `reco` | recommendation-service, recalc-worker |
| `billing` | Plans, subscriptions, entitlements, payments (provider adapter), invoices, webhooks | `billing` | subscription-service |
| `commerce` | Marketplace deals, affiliate clicks/conversions, card applications, ads | `commerce` | marketplace-service, ads-service |
| `audit` | Hash-chained audit log, activity analytics, notifications, privacy export/delete saga | `audit` | activity-service, document-retention-worker |
| `ai-parser` (Python) | Statement PDF → structured transactions (async via Kafka) | — | ai-parser-service |

Why 10 and not 17:
- Each service is one bounded context with one owner of its data.
- Workers (recalc, retention, outbox relay) run as **components inside the owning service**,
  enabled by profile. They can still be deployed separately (`--spring.profiles.active=worker`).
- There are fewer network hops and fewer distributed transactions. This is what enterprise
  teams actually do after overshooting with service count.

### 2.2 Communication rules

- **Sync (REST):** only for user-facing reads that need another service's *current* answer, via
  `RestClient`. Every call has a timeout, retry with jitter (GET only), a circuit breaker and a bulkhead.
- **Async (Kafka, KRaft mode, no ZooKeeper):** every cross-service state propagation.
  Services keep **local projections** of other services' data (for example, reco keeps a copy of
  the catalog reward rules), so hot paths never call other services.
- **No cross-database foreign keys.** Cross-service references are UUIDs, validated at write time
  through a projection or a sync call.

### 2.3 Tech stack (pinned in P00)

| Area | Choice | Notes |
|---|---|---|
| Language | **Java 25 LTS** | Fallback: Java 21 (ADR in P00 if needed). |
| Framework | **Spring Boot 4.0.x**, Spring Framework 7 | Jackson 3 (`tools.jackson.*`), built-in API versioning, `RestClient`, virtual threads on. Fallback: Boot 3.5.x + Java 21. P00 decides and records an ADR. |
| Spring Cloud | 2025.1.x (Gateway, reactive) | Must match Boot 4. |
| Data access | `JdbcClient` + `NamedParameterJdbcTemplate` for batches | **No JPA. No Spring Data repositories.** |
| Migrations | Flyway 11 | Runs as `<svc>_owner`. |
| Database | PostgreSQL 17 (Neon / self-hosted) | Extensions: `pgcrypto`, `citext`, `ltree`, `pg_trgm`, `btree_gist`. |
| Pooling | HikariCP (+ PgBouncer in transaction mode for prod) | RLS context is transaction-local, so it is PgBouncer-safe. |
| Cache/limits | Redis 7 | Rate limits, cache, SSE fan-out (pub/sub), single-flight locks. |
| Events | Kafka 4.x (KRaft), JSON events + JSON Schema in `contracts` | Idempotent producer, `acks=all`. |
| IDs | UUIDv7 generated in the app (`com.github.f4b6a3:uuid-creator`) | Time-ordered and index-friendly. The id is known before insert. |
| Resilience | Resilience4j | |
| Security | Spring Security, Nimbus JOSE (EdDSA/ES256), Argon2id (`spring-security-crypto` + BouncyCastle) | |
| Observability | Micrometer + OpenTelemetry, Prometheus, Grafana, Tempo/Jaeger, JSON logs | |
| Tests | JUnit 5, AssertJ, Testcontainers 2.x, REST Assured, ArchUnit, WireMock, Awaitility, k6 | |
| Build | Maven multi-module reactor, Jib images, GitHub Actions | |
| Python | FastAPI + aiokafka | ai-parser |

### 2.4 Repository layout

```
dexter-v2/
  pom.xml                     # reactor + dependencyManagement (imports platform-bom)
  platform-bom/               # pinned versions
  libs/
    dexter-core/              # money, ids, errors, request context, clock, validation
    dexter-db/                # JdbcClient helpers, TenantTx (RLS), keyset pager, plan-assert, partitions
    dexter-idempotency/       # Idempotency-Key engine
    dexter-events/            # outbox writer, relay, inbox, envelope, topic registry
    dexter-security/          # JWT resource server, AuthContext, step-up, service tokens
    dexter-web/               # ProblemDetail handler, request-id filter, ETag/If-Match, JSON config
    dexter-test/              # Testcontainers base, RLS proof kit, pagination/idempotency contract tests, ArchUnit rules
    portfolio-calc/           # pure-Java calc engines (no Spring) — P12
  contracts/                  # event JSON Schemas + generated records, OpenAPI specs per service
  services/
    gateway/ identity/ ledger/ catalog/ portfolio/ planning/ reco/ billing/ commerce/ audit/
  ai-parser/                  # Python
  seed/                       # deterministic mock-data generator (Java CLI) + fixtures export
  test-ui/                    # THROWAWAY HTML trail (deleted in P19)
  infra/
    compose/ docker-compose.yml (+ profiles: core, obs, trail)
    postgres/ init/ 00-roles.sql (roles per service; dev only)
    kafka/ grafana/ prometheus/ otel/
  scripts/ verify.sh  seed.sh  run.sh  api-smoke.sh
  docs/ adr/  runbooks/  api/
  BUILD_README.md
  MASTER_PLAN.md
```

### 2.5 Package layout inside each service (enforced by ArchUnit)

```
in.dexter.<svc>
  api/        controllers, request/response records, mappers   (may use app)
  app/        use-cases (transaction boundaries live here)     (may use domain, infra ports)
  domain/     pure logic, value objects, policies              (no Spring, no JDBC)
  infra/      jdbc repositories, kafka, http clients, redis    (implements ports)
  config/
```

Rules:
- Controllers never touch `JdbcClient`.
- `domain` imports nothing from Spring.
- Transactions start only in `app`.

---

## 3. Database standards (every service)

### 3.1 Roles — created outside Flyway

The old repo hardcoded role passwords in migrations. That is never done again. Roles are
created by infra (dev: `infra/postgres/init/00-roles.sql` using env vars; prod: ops runbook).

| Role | Login | Rights |
|---|---|---|
| `<svc>_owner` | yes (Flyway only) | Owns schema and tables. Never used at runtime. |
| `<svc>_app` | yes (runtime) | DML on tenant tables through RLS. `NOBYPASSRLS`. No DDL. |
| `<svc>_admin` | yes (admin endpoints) | SELECT through explicit admin policies. Writes only through audited SQL functions. `NOBYPASSRLS`. |
| `<svc>_relay` | yes (outbox relay/partition jobs) | SELECT/UPDATE on `outbox`, partition management functions. |
| `<svc>_readonly` | yes (replicas/BI) | SELECT via policies. |

No role anywhere has `BYPASSRLS`. Admin access is expressed as policies, so it is visible and auditable.

Per-role safety settings:
```sql
ALTER ROLE ledger_app SET statement_timeout = '5s';
ALTER ROLE ledger_app SET lock_timeout = '2s';
ALTER ROLE ledger_app SET idle_in_transaction_session_timeout = '10s';
ALTER ROLE ledger_app SET search_path = ledger, public;
```

### 3.2 The RLS pattern

This goes in every service's first Flyway migration.

```sql
CREATE SCHEMA IF NOT EXISTS ledger AUTHORIZATION ledger_owner;
REVOKE ALL ON SCHEMA ledger FROM PUBLIC;
GRANT USAGE ON SCHEMA ledger TO ledger_app, ledger_admin, ledger_relay, ledger_readonly;

CREATE OR REPLACE FUNCTION ledger.app_account() RETURNS uuid
LANGUAGE sql STABLE PARALLEL SAFE AS
$$ SELECT NULLIF(current_setting('app.account_id', true), '')::uuid $$;

CREATE OR REPLACE FUNCTION ledger.app_actor_role() RETURNS text
LANGUAGE sql STABLE PARALLEL SAFE AS
$$ SELECT NULLIF(current_setting('app.actor_role', true), '') $$;
```

Policy template for tenant tables:
```sql
ALTER TABLE ledger.expenses ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.expenses FORCE  ROW LEVEL SECURITY;

CREATE POLICY expenses_tenant ON ledger.expenses
  FOR ALL TO ledger_app
  USING      (account_id = (SELECT ledger.app_account()))
  WITH CHECK (account_id = (SELECT ledger.app_account()));

CREATE POLICY expenses_admin_read ON ledger.expenses
  FOR SELECT TO ledger_admin
  USING ((SELECT ledger.app_actor_role()) IN ('SUPPORT','SUPER_ADMIN'));
```

Why these details matter:
- `(SELECT fn())` makes Postgres evaluate the function once per query (an InitPlan) instead of
  once per row. This is critical at millions of rows.
- `current_setting(..., true)` returns NULL when the context is unset. The policy then matches
  **zero rows**, so a missing context fails closed.
- `FORCE` applies RLS even to the table owner.
- Every tenant index **starts with `account_id`**, so the policy predicate plus the query predicate
  form one index range scan.

**Pre-authentication lookups** (login by email, refresh-token lookup, webhook provider id) go
through narrow `SECURITY DEFINER` functions. Each one:
- is owned by `<svc>_owner`;
- has `SET search_path` pinned;
- returns the minimum columns;
- is `REVOKE`d from PUBLIC and `GRANT`ed only to `<svc>_app`.

**Global reference tables** (categories, merchants, catalog) have RLS **enabled** with policies such
as `FOR SELECT TO x_app USING (status = 'published')`, plus writes allowed only for admin/promoter roles.

### 3.3 Java side: TenantTx (in `dexter-db`)

```java
public final class TenantTx {
  private final TransactionTemplate rw, ro;
  private final JdbcClient jdbc;

  public <T> T write(Tenant t, Supplier<T> work) { return run(rw, t, work); }
  public <T> T read (Tenant t, Supplier<T> work) { return run(ro, t, work); }

  private <T> T run(TransactionTemplate tpl, Tenant t, Supplier<T> work) {
    return tpl.execute(s -> {
      jdbc.sql("SELECT set_config('app.account_id', :a, true), set_config('app.actor_role', :r, true)")
          .param("a", t.accountId().toString()).param("r", t.role()).query().singleRow();
      return ScopedValue.where(TenantScope.CURRENT, t).call(work::get);
    });
  }
}
```

- `set_config(..., true)` is **transaction-local**. The context disappears at commit or rollback,
  so a pooled connection can never leak one tenant's context to the next borrower.
- Repositories call `TenantScope.require()` and fail fast if there is no tenant or no active
  transaction. The ArchUnit rule: every `*JdbcRepository` public method must be reached through TenantTx.
- `rw` routes to the primary DataSource. `ro` (`readOnly=true`) routes to a replica through
  `LazyConnectionDataSourceProxy` + `AbstractRoutingDataSource`. RLS works the same on replicas.

### 3.4 Table conventions

```sql
id           uuid PRIMARY KEY,                 -- UUIDv7 from app
account_id   uuid NOT NULL,                    -- tenant tables
...
created_at   timestamptz NOT NULL DEFAULT now(),
updated_at   timestamptz NOT NULL DEFAULT now(),
version      integer     NOT NULL DEFAULT 0,   -- optimistic locking (+ HTTP ETag)
deleted_at   timestamptz                       -- soft delete where users can undo/audit
```

- **Money:** `amount_paise bigint NOT NULL CHECK (amount_paise > 0)` (or `>= 0`), plus `currency char(3) NOT NULL DEFAULT 'INR'`.
- **Rates:** basis points `integer` (`rate_bps`). 1% = 100 bps. APR 42% = 4200 bps.
- **Enums:** `text` + `CHECK (x IN (...))`. No Postgres ENUM types, because they are painful to evolve.
- **Time:** always `timestamptz` in UTC. Day bucketing uses the account's timezone (default `Asia/Kolkata`, stored on the account and propagated in the JWT claim `tz`).
- **Updates:** always `UPDATE ... SET ..., version = version + 1, updated_at = now() WHERE id = :id AND version = :expected`. Zero rows updated → 412 (`VERSION_CONFLICT`).
- **Queries:**
  - Explicit column lists. Never `SELECT *`.
  - No N+1: batch-load with `= ANY(:ids)`.
  - Upserts with `INSERT ... ON CONFLICT`.
  - Bulk writes with `batchUpdate` (≤ 1000 rows/batch); imports use `COPY` via `CopyManager`.
  - Job/queue claiming uses `FOR UPDATE SKIP LOCKED`.
  - Singletons (schedulers) use `pg_try_advisory_xact_lock`.

### 3.5 Indexing rules

1. Every tenant index is **composite with `account_id` first**.
2. Every keyset list has an index **exactly matching its ORDER BY**, e.g.
   `(account_id, occurred_at DESC, id DESC)`.
3. Use **partial indexes** for hot subsets: `WHERE deleted_at IS NULL`, `WHERE status = 'active'`,
   `WHERE published_at IS NULL`.
4. Use **covering indexes** (`INCLUDE (...)`) when a list endpoint reads a few narrow columns.
5. **Every foreign key column is indexed.**
6. Text search: `GIN (col gin_trgm_ops)`. Hierarchy: `GIST (category_path)`. Tenant + hierarchy:
   `GIST (account_id, category_path)` via `btree_gist`.
7. Use **BRIN** on `created_at` for huge append-only tables (audit, activity).
8. In production migrations, create indexes with `CREATE INDEX CONCURRENTLY` in a separate
   migration marked non-transactional (Flyway `executeInTransaction=false` via a `.conf` sidecar).
9. **Every phase ships a Query→Index table** (endpoint → SQL → index used), plus a
   **PlanAssert test**. PlanAssert runs `EXPLAIN (FORMAT JSON)` on the *medium* seed (≥ 1M rows in
   the big tables) and fails if any listed big table shows `Seq Scan` or a sort that the index should avoid.

### 3.6 Partitioning policy

- **Partition** append-only, high-volume tables that need cheap retention: `outbox`, `inbox`,
  `audit_log`, `activity_events`, `deal_clicks`, `ad_impressions_raw`, `recommendation_history`.
  - Range by `created_at`: daily or monthly.
  - Partitions are created ahead by a `create_partitions_ahead()` function, which a scheduled job
    runs under an advisory lock.
  - Retention = `DROP` of old partitions. No big `DELETE`s.
- **Do not partition** `expenses` / `card_transactions` initially. With correct composite indexes,
  single tables handle hundreds of millions of rows. The partition trigger is > 500M rows or a new
  retention requirement; the plan for that is documented in P19.

### 3.7 Migrations

- Naming: `V<yyyyMMddHHmm>__<desc>.sql`.
- Expand/contract only. Never drop or rename a column in the same release that stops using it.
- Every migration is re-runnable in tests from an empty DB.
- `flyway.cleanDisabled=true`, except in tests.

### 3.8 Standard system tables (every service that needs them)

`idempotency_keys` (§5), `outbox` (§6), `inbox` (§6), `scheduler_locks` (advisory-lock based, no table needed).

### 3.9 Connection and pool sizing

- Hikari `maximumPoolSize` ≈ `(cores * 2) + 1` per instance. Scale out with instances, not with pool size.
- In production, place PgBouncer (transaction mode) in front. This is safe because the tenant
  context is transaction-local and prepared statements are handled by PgBouncer ≥ 1.21.
- `leakDetectionThreshold=20000` in dev.

### 3.10 Forbidden patterns (grep-checked by `verify.sh static`)

`jakarta.persistence`, `org.hibernate`, `org.springframework.data.jpa`, `JpaRepository`,
`@Entity`, `OFFSET `, `double amount`, `Double amount`, `float `, `SELECT *`, `.block()`,
`BYPASSRLS`, a `PASSWORD '` literal in any migration, `System.out.println`.

---

## 4. Cursor pagination (shared, exact spec)

### 4.1 API contract

**Request:** `GET /v1/expenses?limit=50&cursor=<opaque>&<filters...>`

- `limit`: default 25, max 100. The first call from a client uses 50; later calls use 25.

**Response:**
```json
{
  "data": [ ... ],
  "page": { "limit": 50, "hasMore": true, "nextCursor": "eyJ2Ijox...Q.k3f9..." }
}
```

- No totals. If the UI needs counts, they come from summary endpoints backed by aggregate tables (§8.3).

### 4.2 Cursor format (`dexter-db/KeysetCursor`)

```
payload = base64url(JSON{ v:1, s:"occurred_desc", k:["2026-09-01T10:00:00Z","0192...uuid"],
                          f:"<sha256(filters) first 12 bytes b64>", kid:"c1" })
cursor  = payload + "." + base64url(HMAC-SHA256(key[kid], payload)[0..16])
```

- The signature stops clients from crafting cursors. RLS would already stop a crafted cursor from
  leaking data; this stops probing and garbage input.
- `f` binds the cursor to its filters. Changing filters while reusing an old cursor returns
  400 `CURSOR_FILTER_MISMATCH`.
- `kid` allows rotating the key.

### 4.3 SQL shape (always `limit + 1`)

```sql
SELECT id, occurred_at, amount_paise, merchant_display, category_path, payment_method
FROM ledger.expenses
WHERE account_id = (SELECT ledger.app_account())          -- explicit too; helps the planner
  AND deleted_at IS NULL
  AND (:after_ts IS NULL OR (occurred_at, id) < (:after_ts, :after_id))
  AND (:category IS NULL OR category_path <@ :category::ltree)
ORDER BY occurred_at DESC, id DESC
LIMIT :limit_plus_one;
```

- Index: `(account_id, occurred_at DESC, id DESC) WHERE deleted_at IS NULL`.
- The pager returns `hasMore = rows.size() > limit` and trims the extra row.
- The tie-breaker `id` guarantees a total order: equal timestamps can never cause duplicates or
  skipped rows. Rows inserted above the cursor while the user scrolls do not shift the pages below it.
- For the `(:after_ts IS NULL OR ...)` form, generate **two SQL variants** (first page vs next page)
  instead of the OR, so the planner always gets a clean range condition. This is enforced in `KeysetPager`.

### 4.4 Client window algorithm (trail pages now, KMM app later)

```
state: buffer=[], shown=0, cursor=null, hasMore=true, inflight=null
PAGE=25, FIRST=50, TRIGGER_OFFSET=1   // trigger at item (shown - TRIGGER_OFFSET) → item 24 of 25

init():
  r = fetch(limit=FIRST); buffer += dedupeById(r.data); cursor=r.nextCursor; hasMore=r.hasMore
  shown = min(PAGE, buffer.length)                 // display 1–25, 26–50 already buffered

onItemVisible(i):                                  // i is 1-based
  if i >= shown - TRIGGER_OFFSET:                  // user reached item 24
    if shown < buffer.length: shown = min(shown+PAGE, buffer.length)   // show 26–50 instantly
    if hasMore && !inflight:                       // fetch 51–75 in background
      inflight = fetch(limit=PAGE, cursor).then(r => { buffer += dedupeById(r.data);
                   cursor=r.nextCursor; hasMore=r.hasMore; inflight=null })
  if i >= buffer.length && inflight: showSkeleton(); await inflight
```

The effect is that the client always holds one page more than it shows, so scrolling never waits
on the network. FIRST, PAGE and TRIGGER_OFFSET are config values.

### 4.5 Mandatory pagination contract tests

`dexter-test/PaginationContract` is an abstract test that every list endpoint extends. It checks:

1. The first page with limit 50 returns 50 items and `hasMore=true`.
2. Walking all pages yields every row exactly once, in the right order.
3. **Rows with identical timestamps (200 of them)** produce no duplicates and no gaps.
4. Inserting new rows mid-walk produces no duplicates in later pages.
5. The last page returns `hasMore=false` and a null cursor.
6. A tampered cursor → 400 `CURSOR_INVALID`. A cursor with changed filters → 400 `CURSOR_FILTER_MISMATCH`.
7. `limit=101` → 400. `limit=0` → 400.
8. Tenant B's cursor used by tenant A → rows are still A's only. The RLS proof holds.
9. PlanAssert: the medium seed shows an index scan and no Sort node.

---

## 5. Idempotency (shared, exact spec)

### 5.1 Contract

- Header: `Idempotency-Key: <UUID>`.
  - **Required** on money-related and creating POSTs (listed per phase).
  - Optional elsewhere; mutations without a key are processed normally.
- Keys are scoped per account (or per client id for pre-auth endpoints) and kept for 24h.
- Outcomes:
  - Same key + same request hash → **replay** the stored status, headers and body, with
    `Idempotent-Replayed: true`.
  - Same key + different body → **422** `IDEMPOTENCY_KEY_REUSED`.
  - Same key while the first request is still executing → the second waits on the unique index up
    to `lock_timeout` (2s), then replays. If the timeout expires first → **409**
    `IDEMPOTENCY_IN_PROGRESS` with `Retry-After: 1`.

### 5.2 Table

This table is not partitioned; keys must be unique across time.

```sql
CREATE TABLE <svc>.idempotency_keys (
  account_id      uuid        NOT NULL,
  idem_key        uuid        NOT NULL,
  request_method  text        NOT NULL,
  request_path    text        NOT NULL,
  request_hash    bytea       NOT NULL,          -- sha256(method|path|canonical-json body)
  state           text        NOT NULL CHECK (state IN ('in_progress','completed')),
  response_status integer,
  response_body   jsonb,
  response_headers jsonb,
  locked_until    timestamptz,
  created_at      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (account_id, idem_key)
);
CREATE INDEX idempotency_keys_created_brin ON <svc>.idempotency_keys USING brin (created_at);
-- RLS: tenant policy on account_id, same as §3.2
```

Purge job: batched `DELETE ... WHERE ctid = ANY(ARRAY(SELECT ctid ... WHERE created_at < now()-'24h' LIMIT 5000))`, run under an advisory lock.

### 5.3 Modes

- **Mode A — DB-local operations (default).** One transaction:
  1. `INSERT key (in_progress) ON CONFLICT DO NOTHING RETURNING`.
  2. If a row already exists, compare hashes, then replay or return 422.
  3. Otherwise do the business write, write the outbox row, update the key to `completed` with the response.
  4. Commit.

  If the transaction rolls back, the key disappears with it, so a retry is clean. A concurrent
  duplicate blocks on the unique index until the first commits, then replays.
- **Mode B — operations with external side effects** (the payment provider in P16). The key is
  committed as `in_progress` with `locked_until`. Recovery points are stored as the call progresses.
  Completion happens in a second transaction. Stale `in_progress` rows are resumed or failed by a
  sweeper. This follows the Stripe-style "atomic phases" design.

### 5.4 Implementation

`IdempotencyEngine.execute(tenant, key, request, () -> Response)` is called from `app/` use-cases
inside `TenantTx.write`. Controllers extract the header through the `@IdempotencyKey`
argument resolver. The replayed response is exactly what was stored.

Natural idempotency also applies where it fits: unique `(account_id, client_ref)` on imports, and
unique provider ids on webhooks.

### 5.5 Contract tests (`dexter-test/IdempotencyContract`)

1. Same key twice → one row created, identical response, and the second has the replay header.
2. Same key with a different body → 422.
3. 20 parallel requests with the same key → exactly 1 business row and 1 outbox row.
4. The first attempt fails (500 thrown mid-transaction) → the retry succeeds.
5. A missing key where it is required → 400 `IDEMPOTENCY_KEY_REQUIRED`.
6. Keys are tenant-scoped: the same key used by a different account does not collide.

---

## 6. Events: outbox, relay, inbox

### 6.1 Envelope (CloudEvents-style, JSON)

```json
{ "id":"<uuidv7>", "type":"ledger.expense.created", "version":1, "source":"ledger",
  "time":"2026-09-21T10:00:00Z", "accountId":"<uuid|null>", "aggregateId":"<uuid>",
  "aggregateVersion":3, "traceparent":"00-...", "data":{ ... } }
```

- Schemas live in `contracts/events/<type>.v<n>.json`, with generated Java records.
- Evolution is additive only. Breaking changes require a new `version` and running both for one release.

### 6.2 Topics

- Name: `dexter.<service>.<aggregate>.v1`. 12 partitions (dev: 3).
- **Key = accountId**, or aggregateId for global aggregates. This preserves per-account ordering.
- Retry topics `...retry-1m`, `...retry-10m` and a `...dlq` (non-blocking retries).
- Retention 7 days. Compaction for projection topics where it fits (the catalog snapshot).

### 6.3 Outbox

```sql
CREATE TABLE <svc>.outbox (
  id             uuid        NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  topic          text        NOT NULL,
  message_key    text        NOT NULL,
  event_type     text        NOT NULL,
  payload        jsonb       NOT NULL,
  headers        jsonb       NOT NULL DEFAULT '{}',
  published_at   timestamptz,
  attempts       integer     NOT NULL DEFAULT 0,
  PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);
CREATE INDEX ON <svc>.outbox (created_at, id) WHERE published_at IS NULL;
-- No RLS. <svc>_app: INSERT only. <svc>_relay: SELECT, UPDATE.
```

The relay:
- Runs one active instance per service, holding `pg_try_advisory_lock(hashtext('<svc>.outbox'))`.
  Any other instance stays on standby.
- Polls every 200ms, and immediately when woken by `LISTEN outbox_new` (a trigger sends `NOTIFY` after insert).
- Claims up to 500 rows with `FOR UPDATE SKIP LOCKED`, ordered by `(created_at, id)`.
- Publishes them with the idempotent producer (`enable.idempotence=true`, `acks=all`), then sets `published_at`.
- Retention: partitions older than 3 days are dropped only once `NOT EXISTS (unpublished)` holds.
- Scale-out path (documented, built in P19 only if needed): shard the relay by `hashtext(message_key) % N`.

### 6.4 Inbox (consumer side)

```sql
CREATE TABLE <svc>.inbox (
  consumer   text        NOT NULL,
  event_id   uuid        NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer, event_id)
);
```

- Handling: in one transaction, `INSERT INTO inbox ... ON CONFLICT DO NOTHING`. If no row was
  inserted, skip (duplicate). Otherwise apply the change and commit, then commit the Kafka offset.
- Exactly-once *effects* on top of at-least-once delivery.
- `aggregateVersion` lets projections ignore stale, out-of-order events: `UPDATE ... WHERE projected_version < :v`.
- Retention: purge inbox rows older than 14 days (≫ topic retention) in batches.

### 6.5 Event tests (per phase)

- A write produces an outbox row in the same transaction; a rollback produces none.
- The relay publishes and marks rows. Killing the relay mid-batch leaves no loss (rows are republished; consumers dedupe).
- A consumer receiving the same event twice applies it once.
- An out-of-order older version is ignored.
- A poison message goes to the DLQ after the retries.

---

## 7. Security ("full protection")

### 7.1 Authentication (identity)

- **Passwords:** Argon2id (m=19456 KiB, t=2, p=1, the OWASP baseline).
  - Rehash on login when the parameters change.
  - Breached-password check against a bundled top-100k list (offline).
- **Access token:** JWT, **EdDSA (Ed25519) or ES256**, 10-minute TTL.
  - Claims: `sub` (accountId), `sid`, `aud` (list of services), `scope`, `roles`, `tz`, `tier`,
    `amr`, `auth_time`, `jti`.
- **Refresh token:** opaque, 256-bit random. Only its SHA-256 is stored.
  - 30-day sliding window, 90-day absolute limit.
  - **Rotation on every use with family reuse detection:** reusing an already-rotated token
    revokes the whole family and the session, and emits a security audit event.
- **JWKS:** keys rotate every 30 days.
  - States: `next` → `active` → `retired` (kept until the last token signed with it expires).
  - `/.well-known/jwks.json` is cached by services for 5 minutes and refetched on an unknown `kid`.
- **MFA:** TOTP (RFC 6238, secret AES-GCM encrypted), plus 10 one-time recovery codes (hashed).
  - Admins must use MFA. It is optional for users.
- **Step-up:** sensitive actions require `auth_time` within 10 minutes plus `amr` containing `mfa`
  (when MFA is enrolled). Sensitive actions: password change, MFA disable, account deletion,
  billing changes, session revoke-all.
- **Lockout:** Redis counters per account and per IP.
  - Progressive delay, then a 15-minute lock after 10 failures.
  - Login responses never reveal whether the email exists: same response, and constant time via a dummy hash.

### 7.2 Authorization

- **Zero trust:** every service validates the JWT itself (`dexter-security`). The gateway check is
  an early rejection only.
  - Services **ignore** any identity headers from clients; the gateway strips `X-Account-*` and `X-Actor-*`.
- `AuthContext` comes from the JWT and feeds `Tenant` for RLS. Business code never takes an
  `accountId` from a path or body when acting as the user.
- **Admins:** RBAC with roles `SUPPORT`, `CATALOG_EDITOR`, `FINANCE`, `SUPER_ADMIN` and fine-grained
  permissions. Checked by method security **and** by DB admin policies (defense in depth).
- **Break-glass** (SUPER_ADMIN): time-boxed (1h) elevation, requires a reason and MFA, audited, and
  sends an alert notification.
- **Service-to-service:** client-credentials JWTs from identity (`aud` = the target service,
  5-minute TTL, cached). mTLS is optional in P19.

### 7.3 Edge and API protection

- **Rate limits** (Redis token bucket, Lua script):
  - per IP, per account, and per route class (`auth`: 10/min, `write`: 120/min, `read`: 600/min, `calc`: 60/min).
  - Responses include `RateLimit-Policy` / `RateLimit` headers and 429 with `Retry-After`.
- **Requests:** body size limit of 256 KB (uploads go through presigned URLs). Strict JSON: unknown
  fields → 400, duplicate keys → 400. Bean Validation on every request record.
- **Security headers:** HSTS, `X-Content-Type-Options`, `Referrer-Policy`, and a restrictive CSP for
  the admin console. CORS allowlist per environment (the dev `trail` profile only allows `localhost`).
- **Webhooks:** HMAC-SHA256 signatures with a ±5-minute timestamp tolerance. Replay protection
  through the unique provider event id.
- **SSRF-safe fetcher** for catalog ingestion: allowlisted hosts, private IPs blocked after DNS
  resolution, redirect limit, size and time caps.
- **Uploads:** presigned PUT to object storage (MinIO in dev), content sniffing, size cap, an
  antivirus hook (ClamAV container, optional in dev), and a quarantine bucket until scanned.
- **Idempotency and optimistic locking** everywhere (§5, §3.4). PATCH requires `If-Match: "<version>"`.

### 7.4 Data protection (DPDP Act 2023-aware)

- No PAN or CVV is ever stored. The statement parser **redacts PANs** before persisting any text
  (Luhn-checked regex).
- **Column encryption:** sensitive PII (phone, TOTP secret, statement raw text) uses app-level
  AES-256-GCM with **per-account data keys** (envelope encryption; the KEK comes from Vault or an
  env var in dev). Deleting an account destroys its data key (**crypto-shredding**).
- **Consent records:** purpose, version, timestamp. Data export (JSON) and deletion are both built as a saga (P18).
- **Logs:** PII-masking Logback converter (email, phone, tokens). Never log request bodies on auth endpoints.
- **Secrets:** env vars in dev. Vault (KV + Transit for the KEK) wired in P19. `gitleaks` runs in CI.

### 7.5 Supply chain

- Dependency pinning via the BOM.
- OWASP dependency-check / Trivy image scan in CI.
- CycloneDX SBOM per service.
- Distroless (Jib) images, running as non-root.

---

## 8. Scalability and performance

### 8.1 Targets (verified in P19)

| Metric | Target |
|---|---|
| List endpoints (keyset, 25–50 rows) at 5M expenses / 100k accounts | p99 < 150 ms end-to-end; DB time < 10 ms |
| Write endpoints (with idempotency + outbox) | p99 < 200 ms |
| Recommendation (cached) | p99 < 100 ms. Recompute < 2 s after the triggering event (p95). |
| Throughput per ledger instance (4 vCPU) | ≥ 1,500 req/s mixed |
| Event lag (outbox → consumer applied) | p95 < 1 s |

### 8.2 Techniques used everywhere

- **Stateless services** with virtual threads; horizontal scaling. Sessions live in Redis only for the admin console.
- **Read replicas** for `readOnly` transactions.
- **Aggregate tables** maintained transactionally (daily totals) or by projections (spend profile).
  No large aggregation at request time.
- **Redis cache-aside** with versioned keys (`reco:v1:{account}:{stateVersion}`).
  Single-flight rebuild via `SET NX PX`. Probabilistic early expiry to prevent stampedes.
- **Kafka partitioned by account.** Consumers scale up to the partition count. Recompute
  **debouncing** (coalesce bursts per account within 3s).
- **Backpressure:** bounded executors, bulkheads, circuit breakers. Timeouts on every I/O: DB 5s,
  HTTP 2s connect + 3s read, Redis 200ms.
- **Retention:** partition drops; archive to object storage (Parquet) is documented for later.

### 8.3 No request-time COUNT or SUM over large sets

Totals come from maintained tables:
- `expense_daily_totals` (ledger)
- `budget_period_spend` (planning)
- `card_savings_summary` (portfolio)
- `deal_click_counters` (commerce)

---

## 9. API conventions

- Base path `/v1/...`, JSON, camelCase.
  - Money fields are integer paise named `...Paise`. Rates are integer `...Bps`.
  - Timestamps are ISO-8601 UTC. Dates are `YYYY-MM-DD` in the account's timezone.
- **Errors:** RFC 9457 ProblemDetail: `type`, `title`, `status`, `detail`, `instance`, **`code`**
  (stable `UPPER_SNAKE`), `requestId`, and `errors[]` for field validation.
  Each service keeps its code catalogue in `docs/api/errors.md`.
- **Headers:**
  - Always: `X-Request-Id` (generated if absent, echoed back, placed in the MDC and traces).
  - Where relevant: `Idempotency-Key`, `If-Match`/`ETag`, `Retry-After`, `RateLimit-*`.
- **PATCH** uses JSON Merge Patch (`application/merge-patch+json`) and requires `If-Match`.
- **OpenAPI:** each service has a hand-maintained `contracts/openapi/<svc>.yaml`. A contract test
  verifies that controllers match it (schema validation of responses in API tests).
- Every endpoint carries the `X-Request-Id` header and appears in the service's `.http` file
  (`services/<svc>/http/<svc>.http`) for manual clicking.

---

## 10. Testing strategy

| Layer | Tooling | Required per phase |
|---|---|---|
| Unit (domain, calc) | JUnit + AssertJ | All domain logic. Calc engines use golden fixtures. |
| Repository | Testcontainers Postgres 17 + Flyway | Every SQL statement. |
| RLS proof | `dexter-test/RlsProofKit` | Per tenant table: (1) A can't read B; (2) no context → 0 rows; (3) A can't insert/update with B's account_id (WITH CHECK); (4) `<svc>_app` can't `ALTER TABLE ... DISABLE RLS`, `SET ROLE`, or read other schemas. |
| Pagination | `PaginationContract` (§4.5) | Every list endpoint. |
| Idempotency | `IdempotencyContract` (§5.5) | Every mutating endpoint. |
| Events | Testcontainers Kafka (KRaft) + Awaitility | Every produced and consumed event. |
| API | REST Assured against the running app (random port) + real JWTs from a test JWKS | Every endpoint: happy path, validation, authz, error codes, OpenAPI schema. |
| Architecture | ArchUnit (`dexter-test/ArchRules`) | Layering, no JPA, no `double` money fields, repos only via TenantTx. |
| Query plans | `PlanAssert` on the medium seed | Every list/search query. |
| Security | Negative tests (expired/forged JWT, wrong aud, missing scope, reused refresh token, rate limit) | Per service. |
| Load | k6 scripts in `perf/` | Smoke per phase (10 VUs, 30s); full in P19. |
| Coverage | JaCoCo | ≥ 80% line on `domain` + `app`. Reported, not gamed. |

### `scripts/verify.sh` modes

- **`static`** — no Maven. Forbidden-pattern grep, migration naming, every tenant table has
  ENABLE+FORCE+POLICY (a SQL parser script), and the README status table is present.
- **`unit`** — `mvn -q -DskipITs verify`.
- **`full`** — `docker compose --profile core up -d` → `mvn verify` (ITs included) →
  `scripts/api-smoke.sh` → k6 smoke. It writes **`verify-report.md`** containing a pass/fail
  summary per module, failing test names with the first 30 lines of each failure, and the smoke results.

---

## 11. Mock data and the throwaway HTML trail

### 11.1 Seed generator (`seed/`, Java CLI, deterministic)

- `--seed 42 --size small|medium|large`:

| Size | Contents |
|---|---|
| **small** | 2 demo accounts (`asha@demo.in`, `ravi@demo.in`, password in `.env.example`) × 500 expenses. For the trail and API tests. |
| **medium** | 10k accounts, 1.2M expenses, 30k user cards. For PlanAssert. |
| **large** | 100k accounts, 5M expenses. For P19 load. |

- Realistic India data:
  - ~300 merchant brands with aliases (Swiggy, Zomato, BigBasket, IRCTC, Amazon, Flipkart, Uber, Ola, BESCOM, Jio, …);
  - ltree categories (`food.dining`, `food.delivery`, `travel.rail`, `utilities.electricity`, …);
  - salary-cycle spending patterns, UPI vs card mix, festive-season spikes (Diwali);
  - amounts in paise with realistic distributions;
  - 40 real-looking card products (clearly marked MOCK) with reward rules and caps.
- Output: SQL `COPY` files per service DB (fast bulk load), plus `test-ui/fixtures/*.json` for mock mode.

### 11.2 Trail kit (`test-ui/`) — testing only, deleted in P19

- Static files only, served by `infra/compose` profile `trail` (nginx), port 8099.
  **No service contains or references it.** CI fails on the release branch if `test-ui/` exists.
- `trail.js` shared kit:
  - **Mock/Live toggle.** Mock mode serves fixtures in the browser; Live mode calls the gateway at `localhost:8080`.
  - **Login panel** with a tenant switcher (Asha / Ravi / Admin) to show RLS isolation.
  - **Pagination window component** implementing §4.4, with a **debug panel**: buffer range,
    shown range, cursor (decoded payload), `hasMore`, in-flight fetch, and a request log with timings.
  - **Idempotency tester:** send the same request twice or in parallel with one key, and show the replay header.
  - **Event viewer:** polls a dev-only `/_dev/outbox/recent` endpoint.
    The endpoint exists only in the `dev` profile and has a `@Profile("dev")` guard test.
- **One page per phase**, e.g. `test-ui/p07-ledger.html`, linked from `test-ui/index.html`.

---

## 12. Improvements over the old repo (what "better" means here)

| Area | Old | New |
|---|---|---|
| Data access | JPA in 8 services, JdbcClient in 4 | JdbcClient everywhere, ArchUnit-enforced |
| Tenant isolation | App-layer filter in 14 of 17 services | Postgres RLS on every tenant table + proof tests; admin via policies, no BYPASSRLS |
| Pagination | OFFSET/Page in 6 services | Signed keyset cursors, contract-tested, plan-asserted |
| Money | 17 `double` money fields | `long` paise + bps, BigDecimal rate math |
| Events | Direct Kafka publish (dual-write loss risk) | Transactional outbox + inbox + versioned projections |
| Idempotency | Scattered | One engine, contract-tested, 2 modes |
| Auth | JWT + refresh rotation | + family reuse detection, EdDSA, JWKS rotation, step-up, Argon2id, registration (was missing) |
| Service trust | Services trusted gateway identity headers | Every service validates the JWT; headers stripped |
| Roles in migrations | Hardcoded `PASSWORD` literal (bug) | Roles created by infra; Flyway runs as owner |
| Budgets | Missing `GET /budgets/{id}`; spend computed by sync call | Full CRUD; spend projection from ledger events; alert thresholds |
| Expense ↔ transaction sync | Not built | Auto-match from events, with user confirmation |
| Catalog schema | Flexible JSONB, no rate/fee schema | Normalized reward rules, versioned with no-overlap exclusion constraints |
| Reco | Sync fan-out, cache as storage | Local projections, debounced recompute, explainable scores |
| SSE | Single-instance; browser can't auth | Redis pub/sub fan-out + one-time SSE tickets |
| Calc engines | BigDecimal rupees | Same verified logic in paise, pure library, golden fixtures from the old tests |
| Ads/marketplace | Counters in DB rows | Redis-batched counters, signed click redirects, idempotent conversion postbacks |
| Audit | Activity log | Hash-chained, append-only, partitioned; privacy export/delete saga |
| Verification | Never compiled | Every phase ends `IT-GREEN` in Codespaces with a report |

### Verified logic to port as golden fixtures (from the old repo)

- `budget-service/.../EmiCalculatorTest` — the reference EMI ≈ ₹8,884.88 (P13).
- `marketplace-service/.../SavingsCalculationServiceTest` — ₹75k spend, 10% capped at 5k + 5% capped at 2k → ₹7,000 saving (P17).
- `card-portfolio-service/src/main/java/com/dexter/portfolio/calc/*`:
  LedgerEngine, MinimumDueCalculator, LateFeeAndDelinquencyCalculator (RBI SMA/NPA),
  CreditUtilizationCalculator, RepaymentPathCalculator, MinimumPaymentSimulator (141-cycle case),
  ExitPathCalculator, NoPaymentSimulator, FullPaymentCalculator, PartialPaymentCalculator,
  CustomFeeCalculator, plus their tests (P12).
- `recommendation-service` — 60/40 scoring blend, WalletOptimizerService (dominance pruning + beam top-15, exclusive category assignment) (P14/P15).
- `auth-service` TOTP implementation, cross-checked against RFC 6238 test vectors (P05).
- `expense-service` merchant seed data and the pg_trgm resolver query shape (P08).

---

## 13. Phase roadmap (P00–P19): one chat per phase

Each phase lists: **Goal · Steps · Schema · Endpoints · Events · Query→Index · Tests · Trail · DoD**.
"Idem: REQ" means `Idempotency-Key` is required on that endpoint.

---

### P00 — Bootstrap, toolchain, infrastructure

**Goal:** an empty but real reactor that builds in Codespaces, with the full local infrastructure running.

**Steps**
1. Pin versions. Check that Boot 4.0.x + Spring Cloud 2025.1.x + Java 25 + Testcontainers 2.x +
   Flyway 11 resolve together. If anything conflicts, fall back to Boot 3.5.x / Java 21 and write
   **ADR-0001**.
2. Reactor `pom.xml`, `platform-bom`, empty module skeletons for every lib and service (each with
   an `Application` class and a health endpoint), `.editorconfig`, and Maven Enforcer
   (dependency convergence, banned deps: hibernate, spring-data-jpa).
3. `infra/compose/docker-compose.yml` with profiles:
   - `core`: postgres:17, redis:7, kafka 4.x KRaft single node, minio, mailpit;
   - `obs`: otel-collector, prometheus, grafana, tempo;
   - `trail`: nginx serving `test-ui`.
4. `infra/postgres/init/00-roles.sql.tmpl` plus a render script that generates the per-service
   database and the 5 roles (§3.1) from `.env`. Also the per-role timeouts.
5. `scripts/verify.sh` (static/unit/full + report), `scripts/run.sh`, `.env.example`.
6. `.devcontainer/devcontainer.json` for Codespaces: Java, Maven, docker-in-docker, k6.
7. GitHub Actions: build + unit on PR; IT on main; gitleaks; Trivy (can be skipped until images exist).
8. `test-ui/index.html` stub + `trail.js` skeleton (toggle, request log).
9. Create `BUILD_README.md` from Appendix A. Write ADRs 0001 (versions) and 0002 (service map, §2.1).

**Tests:** a context-load smoke test per service; `verify.sh static` passes; `docker compose --profile core up` is healthy.

**DoD:**
- [ ] `./scripts/verify.sh full` green in Codespaces.
- [ ] All containers healthy.
- [ ] Every service returns `/actuator/health` = UP.
- [ ] README created.

---

### P01 — Core libraries A: `dexter-core`, `dexter-db`, `dexter-web`

**Goal:** the building blocks every service uses.

**Steps**
1. `dexter-core`:
   - `Money` (paise, add/sub, safe multiply by bps with HALF_EVEN, formatting for logs only);
   - `Bps`; `Ids` (UUIDv7);
   - `Clock` provider (injectable, for tests);
   - `Tenant` record (accountId, role, tz);
   - `DexterException` hierarchy mapped to error codes.
2. `dexter-db`:
   - `TenantTx` (§3.3) with a primary/replica routing DataSource;
   - `TenantScope` (ScopedValue) guard;
   - `Sql` helpers (named params, `= ANY(:ids)` for uuid[], ltree/jsonb/bytea binding helpers);
   - RowMapper utilities for records;
   - `OptimisticUpdate` helper (version check → 412);
   - `KeysetCursor` (HMAC, kid rotation, filter binding) + `KeysetPager` (first/next SQL variants, limit+1);
   - `PartitionManager` (create-ahead / drop-older, advisory-locked);
   - `PlanAssert`.
3. `dexter-web`:
   - ProblemDetail advice with stable codes;
   - request-id filter + MDC;
   - strict JSON config (unknown/duplicate keys → 400);
   - `If-Match`/`ETag` support;
   - `PageResponse<T>` record;
   - limit validation;
   - PII-masking log converter.
4. SQL templates in `dexter-db/src/main/resources/sql-templates/`: base functions (§3.2), outbox/inbox/idempotency DDL.

**Tests:**
- Money/bps rounding tables.
- Cursor sign/verify/tamper/kid rotation.
- `KeysetPager` against a Testcontainers table with duplicate timestamps.
- `TenantTx` proves the context resets after commit and after rollback on the same pooled
  connection (pool size 1).
- PlanAssert catches a Seq Scan.

**DoD:**
- [ ] IT-GREEN.
- [ ] Pooled-connection context-leak test passes.
- [ ] Cursor tamper test passes.

---

### P02 — Core libraries B: `dexter-idempotency`, `dexter-events`, `dexter-security`, ArchUnit

**Steps**
1. `IdempotencyEngine` Mode A + Mode B skeleton, `@IdempotencyKey` resolver, purge job.
2. `dexter-events`:
   - Envelope;
   - `OutboxWriter` (same transaction; fails if no transaction is active);
   - `OutboxRelay` (advisory-lock leader, LISTEN/NOTIFY wake-up, SKIP LOCKED batch, idempotent producer);
   - `InboxGuard`; `ProjectionVersionGuard`;
   - retry/DLQ topic config; `TopicRegistry`.
3. `contracts/`: JSON Schema → record generation (jsonschema2pojo, or hand-written records plus a
   schema validation test).
4. `dexter-security`:
   - resource server with JWKS caching and unknown-kid refetch;
   - `AuthContext`; audience check per service;
   - `@RequiresStepUp`; `@RequiresPermission`;
   - service-token client (client-credentials, cached);
   - a **test JWKS/JWT factory** for tests.
5. `dexter-test`: Testcontainers singletons (PG, Kafka KRaft, Redis), `RlsProofKit`,
   `PaginationContract`, `IdempotencyContract`, `ArchRules`.

**Tests:**
- Idempotency contract against a sample table, including 20 parallel requests.
- Outbox/relay/inbox end-to-end with Kafka, including kill-mid-batch.
- JWT: expired, wrong aud, wrong kid, `none` alg, and a key-rotation scenario.

**DoD:**
- [ ] IT-GREEN.
- [ ] A sample "demo" module inside `dexter-test` uses every lib together (a template that later services copy).

---

### P03 — Test harness, seed generator, trail kit

**Steps**
1. `seed/` CLI:
   - deterministic generators (§11.1) for identity (accounts with Argon2 hashes precomputed once),
     ledger, catalog and portfolio;
   - writes COPY files and JSON fixtures;
   - `scripts/seed.sh small|medium|large` loads them using the owner role.
2. Merchant, alias and category datasets as versioned CSV in `seed/data/`.
3. Mock card catalog: 40 products, clearly `MOCK_` prefixed, with reward rules and caps.
4. `trail.js` complete:
   - pagination window (§4.4) + debug panel;
   - login/tenant switcher;
   - idempotency tester;
   - request log;
   - event viewer stub;
   - fixture-backed mock API router (path → fixture handler that supports cursor paging, so mock mode behaves like live).
5. `perf/k6/` base script + a thresholds template.

**Tests:**
- Seed determinism (same seed → same checksum).
- Medium seed loads in under 10 minutes in Codespaces.
- The trail's mock pager passes the same walk-all test in a headless run (Node/Playwright script `test-ui/tests/pager.spec.js`).

**DoD:**
- [ ] `seed.sh medium` works.
- [ ] `pager.spec.js` green.
- [ ] Trail index opens with mock data.

---

### P04 — Identity A: accounts, registration, login, sessions, tokens

**Schema (`identity`)**
- `accounts`:
  - `id`, `email citext UNIQUE`, `phone_enc bytea`, `phone_hash bytea UNIQUE NULL`, `password_hash`;
  - `status CHECK(pending_verification, active, locked, disabled, deletion_pending, deleted)`;
  - `email_verified_at`, `tz DEFAULT 'Asia/Kolkata'`, `tier_cache`, timestamps, `version`.
- `account_keys (account_id PK, dek_enc bytea, kek_id, created_at, destroyed_at)` — per-account data key.
- `verification_tokens (id, account_id, purpose CHECK(email_verify,password_reset), token_hash UNIQUE, expires_at, used_at)`.
- `sessions (id, account_id, device_label, ua_hash, ip_first inet, created_at, last_seen_at, revoked_at, revoke_reason)`, index `(account_id, created_at DESC, id DESC)`.
- `refresh_tokens (id, session_id, family_id, token_hash UNIQUE, parent_id, issued_at, expires_at, absolute_expires_at, rotated_at, revoked_at)`, index `(family_id)`.
- `signing_keys (kid PK, alg, public_jwk jsonb, private_enc bytea, status CHECK(next,active,retired), created_at, activated_at, retired_at)`.
- RLS: tenant policies on accounts (self), sessions, refresh_tokens (via session → account_id column
  denormalized), verification_tokens.
- Pre-auth functions:
  - `auth_find_login(email) SECURITY DEFINER` → (id, password_hash, status, mfa_enabled);
  - `auth_find_refresh(token_hash)`;
  - `auth_consume_verification(token_hash)`.

**Endpoints**
- `POST /v1/auth/register` (Idem: REQ, client-scoped key; always 202, emails through Mailpit).
- `POST /v1/auth/verify-email`, `POST /v1/auth/login`, `POST /v1/auth/token/refresh`.
- `POST /v1/auth/logout`, `POST /v1/auth/logout-all` (step-up).
- `POST /v1/auth/password/forgot`, `POST /v1/auth/password/reset`, `POST /v1/auth/password/change` (step-up).
- `GET /v1/me`, `PATCH /v1/me` (tz, display name; If-Match).
- `GET /v1/me/sessions` (keyset), `DELETE /v1/me/sessions/{id}`.
- `GET /.well-known/jwks.json`.
- Internal: `POST /internal/v1/service-token` (client credentials).

**Events:** `identity.account.registered`, `identity.account.verified`, `identity.session.revoked`,
`identity.security.alert` (refresh reuse, lockout).

**Other steps:**
- Key rotation job.
- Argon2 rehash on login.
- Constant-time login path.
- Breached-password list.

**Tests:**
- Full auth flow.
- Refresh rotation and **reuse detection revokes the family**.
- Enumeration-safe responses (timing within tolerance).
- JWKS rotation with tokens from the old key still valid until expiry.
- RLS proof on all tenant tables.
- Sessions pagination contract.

**Trail:** `p04-identity.html` — register → verify (Mailpit link) → login → refresh → replay an old
refresh token and watch the family get revoked.

**DoD:**
- [ ] IT-GREEN.
- [ ] Reuse-detection test passes.
- [ ] No endpoint leaks whether an email exists.

---

### P05 — Identity B: MFA, lockout, admins, RBAC, break-glass

**Schema**
- `mfa_totp (account_id PK, secret_enc, confirmed_at, last_used_step bigint)` — the step counter prevents TOTP replay.
- `recovery_codes (account_id, code_hash, used_at)`.
- `admins (id, email citext UNIQUE, password_hash, status, mfa_required true, ...)`.
- `roles`, `permissions`, `role_permissions`, `admin_roles`.
- `elevations (id, admin_id, reason, approved_by NULL, starts_at, expires_at, revoked_at)`.

**Endpoints**
- `POST /v1/me/mfa/totp/enroll` (returns an otpauth URI + QR data), `.../confirm`, `DELETE /v1/me/mfa/totp` (step-up).
- `POST /v1/me/mfa/recovery-codes` (regenerate, step-up).
- `POST /v1/auth/mfa/verify` (second factor of login).
- `POST /v1/auth/step-up`.
- Admin:
  - `POST /v1/admin/auth/login` → MFA mandatory;
  - `GET/POST/PATCH /v1/admin/admins` (keyset);
  - `POST /v1/admin/elevations` (break-glass, reason required);
  - `GET /v1/admin/accounts?email=` (support lookup; audited event).

**Other:** Redis lockout (progressive), per-IP limits, and security alert events.

**Tests:**
- RFC 6238 test vectors.
- Replay of the same TOTP step is rejected.
- Recovery codes are single-use.
- Lockout thresholds.
- Permission matrix tests.
- Break-glass expiry.

**Trail:** `p05-mfa-admin.html`.

**DoD:**
- [ ] IT-GREEN.
- [ ] Admin endpoints are unreachable without MFA + permission.

---

### P06 — Gateway

**Steps**
1. Routes for all services with multi-prefix predicates. Every path is listed in `docs/api/routes.md`
   and checked by a test; this fixes the old 404/401 bugs by construction.
2. Filters, in order:
   1. request-id;
   2. strip identity headers;
   3. body-size limit;
   4. JWT pre-validation (JWKS) with a public-route allowlist;
   5. Redis token-bucket rate limiter (Lua) per IP / account / route class;
   6. circuit breaker per route (**excluding SSE routes**);
   7. response security headers;
   8. CORS per profile.
3. SSE route with long timeouts. No circuit breaker or time limiter on it.
4. Gateway emits `activity` events to Kafka, sampled, asynchronous, and never blocking.

**Tests:**
- Route table test: every documented path reaches the right service (WireMock backends).
- Rate-limit 429 + headers.
- Spoofed `X-Account-Id` is stripped.
- A public route works without a token.
- SSE isn't cut at 10s.

**DoD:**
- [ ] IT-GREEN.
- [ ] `docs/api/routes.md` complete.

---

### P07 — Ledger A: expenses core (the reference implementation)

This phase is **the pattern every later service copies**: RLS, keyset paging, idempotency, outbox,
ETag and aggregates.

**Schema (`ledger`)**
- `categories (id, account_id NULL, path ltree NOT NULL, name, icon, is_system, UNIQUE NULLS NOT DISTINCT (account_id, path))`
  - RLS: `USING (account_id IS NULL OR account_id = app_account())`.
  - `GIST(path)`.
- `expenses`:
  - `id`, `account_id`, `occurred_at`, `amount_paise CHECK(>0)`, `currency`;
  - `merchant_raw text`, `merchant_display text`, `merchant_id uuid NULL`;
  - `category_path ltree NOT NULL DEFAULT 'general'`, `category_source CHECK(user,resolver,import,rule)`, `resolution_confidence smallint (0–1000)`;
  - `payment_method CHECK(card,upi,cash,netbanking,wallet,other)`, `user_card_id uuid NULL`;
  - `note text CHECK(length<=500)`, `tags text[] CHECK(cardinality<=10)`;
  - `client_ref text NULL`, `source CHECK(manual,import,statement,sync)`;
  - timestamps, `version`, `deleted_at`.
- `expense_daily_totals (account_id, day date, category_root ltree, total_paise bigint, txn_count int, PRIMARY KEY(account_id, day, category_root))`.
  Maintained **in the same transaction** as every expense write through a delta upsert (handles
  create, update — including a moved day or category — and delete).
- `categorization_rules (id, account_id, match_type CHECK(merchant_id,contains), pattern, category_path, priority)`.
  User rules are applied before the resolver. Built in P08; the table is created now.

**Indexes**
- `expenses_list`: `(account_id, occurred_at DESC, id DESC) WHERE deleted_at IS NULL`.
- `expenses_cat`: `GIST (account_id, category_path) WHERE deleted_at IS NULL`.
- `expenses_merchant`: `(account_id, merchant_id, occurred_at DESC) WHERE deleted_at IS NULL AND merchant_id IS NOT NULL`.
- `expenses_client_ref`: `UNIQUE (account_id, client_ref) WHERE client_ref IS NOT NULL`.
- `expenses_card`: `(account_id, user_card_id, occurred_at DESC) WHERE user_card_id IS NOT NULL AND deleted_at IS NULL`.

**Endpoints**
- `POST /v1/expenses` (Idem: REQ).
- `GET /v1/expenses` (keyset; filters: from, to, category (subtree), paymentMethod, cardId, merchantId, minPaise, maxPaise).
- `GET /v1/expenses/{id}` (with ETag).
- `PATCH /v1/expenses/{id}` (If-Match, merge-patch).
- `DELETE /v1/expenses/{id}` (soft) and `POST /v1/expenses/{id}:restore`.
- `GET /v1/categories`, `POST /v1/categories` (custom subtree under a system root).

**Events:** `ledger.expense.created|updated|deleted|restored` v1, with full after-state + before-state
(amount, day, category, card) so consumers can compute deltas.

**Query→Index table** required in the README.

**Tests:**
- RLS proof (expenses, categories, daily totals).
- `PaginationContract`.
- `IdempotencyContract`.
- Daily totals always equal `SUM` over expenses (a property test with 1,000 random ops).
- PlanAssert on the medium seed.
- ETag 412.
- Outbox rows per write.

**Trail:** `p07-ledger.html` — the full 50/25/24 window with the debug panel, CRUD, the idempotency
tester, a tenant switch showing the isolation, and daily-totals live.

**DoD:**
- [ ] IT-GREEN.
- [ ] PlanAssert green on ≥ 1M rows.
- [ ] Trail exercised in both mock and live modes.

---

### P08 — Ledger B: merchants, resolver, search, bulk, import, calendar, summaries

**Schema**
- `merchants (id, canonical_name, normalized_name, default_category ltree, logo_key, status)` — global.
- `merchant_aliases (alias_normalized, merchant_id, weight)`:
  - `GIN (alias_normalized gin_trgm_ops)`;
  - `UNIQUE(alias_normalized, merchant_id)`.
- `import_jobs (id, account_id, status, file_key, total, ok, failed, created_at, finished_at)`.
- `import_errors (job_id, row_no, code, detail)`.

**Resolver**
- Pipeline: normalize (lowercase, strip UPI handles, city suffixes, `*`, POS prefixes) → user
  `categorization_rules` → exact alias → trigram similarity (`%` operator with
  `set_limit(0.45)`, ordered by `similarity DESC`, `LIMIT 5`) → fallback `general`.
- Confidence is 0–1000 and always populated.
- An LRU cache of resolved aliases in Redis.

**Endpoints**
- `GET /v1/merchants/autocomplete?q=` (≥ 2 chars, top 8, 50ms budget, cached).
- `POST /v1/merchants/resolve`.
- `GET /v1/expenses/search?q=` (keyset; trigram on `merchant_display` + note within the account; date-bounded default of 12 months).
- `POST /v1/expenses:bulk` (≤ 500 items, Idem: REQ, per-item result, `client_ref` natural idempotency).
- `POST /v1/imports` (CSV via presigned upload → async job, COPY into a staging table, validate, merge).
- `GET /v1/imports/{id}`.
- `GET /v1/calendar?month=2026-09` (from daily totals + upcoming liabilities projection, merged per day).
- `GET /v1/summary?from&to&groupBy=category|merchant|day|paymentMethod`.
- `POST /v1/rules`, `GET /v1/rules`.

**Indexes:** trigram on `merchant_display`, plus `(account_id, occurred_at)` bounded search.
Search uses the tenant + date btree first, then a trigram recheck; PlanAssert validates this.

**Events:** `ledger.import.completed`. Bulk emits individual expense events (batched outbox insert).

**Tests:**
- Resolver precision on a labelled set of 500 raw merchant strings (≥ 90% top-1 on the seed).
- Autocomplete latency on the medium seed.
- Import of 10k rows is idempotent (re-uploading the same file makes no duplicates).
- Bulk partial failure semantics.

**Trail:** `p08-ledger-plus.html` — autocomplete, search with paging, CSV import progress, calendar grid.

**DoD:**
- [ ] IT-GREEN.
- [ ] Resolver accuracy report in the README.

---

### P09 — Catalog A: normalized, versioned card catalog

**Schema (`catalog`)** — global data. RLS: public role reads `status = 'published'`; the editor role writes drafts.

- `issuers (id, code UNIQUE, name, type CHECK(bank,nbfc,cobrand))`.
- `card_products`:
  - `id`, `issuer_id`, `slug UNIQUE`, `name`, `network CHECK(visa,mastercard,rupay,amex,diners)`, `tier`;
  - fees: `joining_fee_paise`, `annual_fee_paise`, `fee_waiver_spend_paise`;
  - rates: `apr_bps`, `forex_markup_bps`, `fuel_surcharge_waiver_bps`;
  - `features jsonb` (non-computational extras only);
  - `status CHECK(draft,published,retired)`, `current_version int`, `content_hash bytea`.
- `card_product_versions (card_product_id, version, snapshot jsonb, effective_from, effective_to, PRIMARY KEY(card_product_id, version))`.
- `reward_rules`:
  - `id`, `card_product_id`, `version`;
  - scope: `category_path ltree NULL`, `merchant_id uuid NULL`;
  - value: `reward_type CHECK(cashback,points,miles)`, `rate_bps`, `point_value_milli_paise`;
  - caps: `cap_paise`, `cap_period CHECK(txn,statement,month,quarter,year)`, `min_txn_paise`;
  - `excluded_paths ltree[]`, `valid tstzrange`, `priority`.
  - **EXCLUDE USING gist (card_product_id WITH =, category_path WITH =, valid WITH &&)** for the same
    scope, so there are never overlapping versions.
- `offers`:
  - `id`, `issuer_id`, `card_product_id NULL`, `merchant_id NULL`, `category_path NULL`;
  - `kind CHECK(pct,flat,cashback)`, `value_bps|value_paise`, `max_discount_paise`, `min_spend_paise`;
  - `valid tstzrange`, `stackable bool`, `terms_url`, `status`, `version`.
- `milestones (card_product_id, spend_threshold_paise, period, benefit_type, benefit_value_paise)`.

**Endpoints**
- Public:
  - `GET /v1/catalog/cards` (keyset; filters network, issuer, maxAnnualFee, category-earning ≥ X bps);
  - `GET /v1/catalog/cards/{slug}`;
  - `GET /v1/catalog/cards/search?q=` (trigram name);
  - `GET /v1/catalog/offers?merchantId|category` (active only);
  - `GET /v1/catalog/categories/potential?category=` (best published rate — replaces the old reco endpoint).
- Admin (CATALOG_EDITOR): CRUD on drafts, `POST /v1/admin/catalog/cards/{id}:publish`
  (creates a version, closes the previous validity range, emits an event).

**Events:** `catalog.card.published` (full snapshot including rules — a compacted topic used for
projections) and `catalog.offer.changed`.

**Tests:**
- Exclusion constraint rejects overlaps.
- Publish produces an immutable version.
- Public role can't see drafts (RLS proof variant).
- Search plan.

**Trail:** `p09-catalog.html`.

**DoD:**
- [ ] IT-GREEN.
- [ ] Snapshot event consumed by a test consumer builds an identical projection.

---

### P10 — Catalog B: ingestion pipeline (collector → review → promoter)

**Schema**
- `sources (id, issuer_id, url, kind CHECK(html,pdf,sitemap,image), schedule_cron, enabled)`.
- `crawl_runs (id, started_at, status, checkpoint jsonb)`.
- `snapshots (id, source_id, fetched_at, content_hash, storage_key)` — dedupe by hash.
- `extractions (id, snapshot_id, card_product_id NULL, extracted jsonb, extractor_version, confidence)`.
- `review_items (id, extraction_id, diff jsonb, status CHECK(pending,approved,rejected,superseded), reviewer_id, decided_at)`, keyset queue.
- `promotions (id, review_item_id, card_product_id, new_version, promoted_at)`.

**Rules**
- The **promoter is the only writer to published data**.
- The collector runs with the SSRF-safe fetcher, hash-based diffing, resumable checkpoints, and a
  per-host politeness delay.
- Field-level diff against the current published version.
- The review queue is admin-only and uses keyset paging.
- Approve → the promoter creates a new version (P09 publish path).

**Endpoints:** `/v1/admin/ingestion/sources` CRUD, `POST .../runs`, `GET /v1/admin/review` (keyset),
`POST /v1/admin/review/{id}:approve|reject`.

**Tests:**
- Unchanged content → no review item.
- Changed fee → one review item with the right diff.
- The promoter is idempotent (a double approve creates one version).
- SSRF tests (localhost, 169.254.x, redirect to a private address).

**Trail:** `p10-ingestion.html` — a mock source in fixtures; approve and watch a new version publish.

**DoD:**
- [ ] IT-GREEN.

---

### P11 — Portfolio A: user cards, statements, transactions, EMI tracker, rewards ledger

**Schema (`portfolio`)**
- `user_cards`:
  - `id`, `account_id`, `card_product_id`, `nickname`;
  - `last4 char(4) CHECK(last4 ~ '^[0-9]{4}$')`, `network`;
  - `statement_day smallint CHECK(1–28 or 31=last)`, `due_offset_days`, `credit_limit_paise`;
  - `opened_on`, `status`, `version`;
  - `UNIQUE(account_id, card_product_id, last4)`.
- `statements (id, account_id, user_card_id, period_start, period_end, due_date, total_due_paise, min_due_paise, status CHECK(open,generated,paid,partially_paid,overdue))`.
- `card_transactions`:
  - `id`, `account_id`, `user_card_id`, `posted_at`;
  - `amount_paise`, `direction CHECK(debit,credit)`, `description`;
  - `merchant_id`, `category_path`, `expense_id NULL`, `statement_id NULL`;
  - `client_ref`, `source`.
- `expense_links (account_id, expense_id, card_txn_id, status CHECK(suggested,confirmed,rejected), score)`.
  **Auto-match** from `ledger.expense.*` events: same card, amount, ±2 days, merchant similarity → suggestion.
- `emi_plans`, `emi_schedule` (keyset).
- `reward_points_ledger (id, account_id, user_card_id, kind CHECK(earn,redeem,expire,adjust), points bigint, value_milli_paise, expires_on, source_ref)`.
- `card_savings_summary (account_id, user_card_id, period month, rewards_value_paise, fees_paise)`.
- Projection: `catalog_products_proj` (from `catalog.card.published`).

**Endpoints**
- `/v1/cards` CRUD (Idem: REQ on create).
- `/v1/cards/{id}/statements` (keyset).
- `/v1/cards/{id}/transactions` (keyset) + `POST` (Idem: REQ).
- `/v1/links/suggestions` (keyset) + `:confirm|:reject`.
- `/v1/emi-plans` CRUD + `/{id}/schedule` (keyset).
- `/v1/rewards/balance`, `/v1/rewards/ledger` (keyset, with expiring-soon).
- `/v1/portfolio/report?period=month|year`.

**Events:** `portfolio.card.added|removed|updated`, `portfolio.transaction.recorded`, `portfolio.link.confirmed`.

**Tests:**
- RLS proof on all tables.
- Pagination and idempotency contracts.
- Auto-match accuracy on the seed.
- Projection version guard.

**Trail:** `p11-portfolio.html`.

**DoD:**
- [ ] IT-GREEN.

---

### P12 — Portfolio B: calc engine library + statement upload + ai-parser

**Steps**
1. `libs/portfolio-calc`: port **every** old calc engine (§12 list) to pure Java.
   - Paise `long` at the boundaries; BigDecimal internally with the same rounding the old verified code used.
   - Port all old tests as **golden fixtures** (values converted to paise). Any mismatch must be
     explained in an ADR — never silently "fixed".
2. `/v1/calc/*` stateless endpoints: rate-limited `calc` class, no DB, `POST` only, request size caps.
3. Statement upload:
   1. `POST /v1/statements/uploads` → presigned PUT (MinIO) + an `upload_id`;
   2. `POST /v1/statements/uploads/{id}:submit` (Idem: REQ) → scan → Kafka `portfolio.statement.submitted`;
   3. the Python ai-parser consumes it, extracts transactions (**PAN redaction first**) and returns `portfolio.statement.parsed`;
   4. the result is staged as `parsed_statements` + `parsed_lines`;
   5. `GET` for user review, then `:confirm` → card_transactions (idempotent by line hash).
4. ai-parser: FastAPI health endpoint, an aiokafka consumer, a deterministic rule-based parser for 3
   mock bank formats (a real OCR/LLM extractor plugs in behind the same interface later), and pytest tests.

**Tests:**
- Golden fixtures (including the 141-cycle MinimumPaymentSimulator and ExitPathCalculator to the rupee).
- PAN redaction (Luhn).
- The parser on mock statements.
- Upload flow end-to-end with a mock scan.

**Trail:** `p12-calc-statements.html` — calculators with inputs, plus upload → review → confirm.

**DoD:**
- [ ] IT-GREEN.
- [ ] 100% of the old calc tests ported and green.

---

### P13 — Planning: budgets, spend projection, liabilities, sandbox, alerts

**Schema (`planning`)**
- `budgets`:
  - `id`, `account_id`, `name`, `period CHECK(weekly,monthly,custom)`, `category_path ltree NULL` (NULL = total);
  - `limit_paise`, `rollover bool`, `alert_thresholds smallint[] DEFAULT '{50,80,100}'`;
  - `starts_on`, `ends_on NULL`, `status`, `version`.
- `budget_period_spend (account_id, budget_id, period_start date, spent_paise, projected_version bigint, PRIMARY KEY(...))`.
  Updated from ledger events with a delta per matching budget, via the inbox and version guard.
  Uses the account timezone for period boundaries.
- `budget_alerts_sent (budget_id, period_start, threshold, sent_at, PRIMARY KEY(...))` — each threshold alerts at most once per period.
- `liabilities (id, account_id, kind CHECK(loan,card_emi,bnpl,other), lender, principal_paise, apr_bps, tenure_months, start_date, emi_paise, status, version)`.
- `liability_schedule`.
- `scenarios (id, account_id, name, base_snapshot jsonb, adjustments jsonb, created_at)` — copy-on-read, never a live view.

**Endpoints**
- `/v1/budgets` full CRUD including **`GET /v1/budgets/{id}`** (missing in the old repo).
- `GET /v1/budgets/status` (all current periods).
- `GET /v1/budgets/{id}/history` (keyset).
- `/v1/liabilities` CRUD + `/{id}/schedule`.
- `POST /v1/calc/emi`.
- `/v1/scenarios` CRUD + `POST /{id}:evaluate`.
- `GET /v1/budgets/category-potential?category=` (a read-through to catalog potential, cached).

**Events:** `planning.budget.threshold_crossed` (consumed by the audit/notification service), `planning.liability.changed`.

**Tests:**
- EMI golden ₹8,884.88.
- Projection equals a recomputed `SUM` (property test).
- Period boundary in IST: an expense at 23:30 IST on the 31st lands in the right month.
- Thresholds alert once.
- RLS, pagination and idempotency contracts.

**Trail:** `p13-planning.html` — budget bars updating live as expenses are added in the ledger panel.

**DoD:**
- [ ] IT-GREEN.

---

### P14 — Reco A: projections, spend profile, scoring, explainability

**Schema (`reco`)**
- `spend_profile (account_id, category_root ltree, window CHECK(3m,6m,12m), monthly_avg_paise, txn_count, projected_version, PRIMARY KEY(account_id, category_root, window))`.
  Built from ledger events.
- `held_cards_proj (account_id, user_card_id, card_product_id, projected_version)` — from portfolio.
- `catalog_rules_proj` — reward rules + caps from the catalog snapshot topic.
- `rec_state (account_id PK, ledger_ver, portfolio_ver, catalog_ver, dirty bool, dirty_since, computed_at)`.
- `recommendations (account_id PK, state_hash, computed_at, payload jsonb)` — latest result.
- `recommendation_history` — partitioned monthly, 180-day retention.

**Engine** (`reco/domain`, pure, deterministic)
- Per card: savings = Σ over categories of `min(spend × rate, cap per period)` − (annual fee after the
  waiver probability) + milestone value.
- Expense fit = cosine-like overlap of the reward vector with the spend vector.
- Both components are normalized to 0–1, then combined with the **60/40 blend** (configurable).
- **Explainability:** each result carries a per-category breakdown, the caps hit, the fee impact and the top reasons.
- **Debounced recompute:**
  1. an event marks `dirty`;
  2. a sweeper claims dirty accounts older than 3s using `SKIP LOCKED` (batch 200);
  3. it recomputes and stores the result, and emits `reco.recommendation.updated`.

**Endpoints**
- `GET /v1/recommendations` (cached; **not-recommended** bucket below a threshold).
- `GET /v1/recommendations/explain/{cardProductId}`.
- `GET /v1/recommendations?cardProductIds=` (score a subset, e.g. search results).

**Tests:**
- Scoring golden cases (cap binding, fee waiver).
- Debounce coalesces 50 events into 1 recompute.
- Projections ignore stale versions.
- Freshness correctness.

**Trail:** `p14-reco.html` — add expenses and watch the recommendation plus its explanation update.

**DoD:**
- [ ] IT-GREEN.

---

### P15 — Reco B: wallet optimizer, best-card-now, SSE

**Steps**
1. Wallet optimizer:
   - dominance pruning;
   - beam top-15;
   - exhaustive search over 2- and 3-card combos;
   - exclusive category assignment (each category goes to its best card, with no double counting);
   - caps respected per card per period;
   - result includes the marginal value of adding each next card.
2. **Best card now:** `GET /v1/recommendations/best-card?merchantId|category&amountPaise=`.
   - Among held cards, it considers remaining cap headroom this period (from the portfolio projection
     plus ledger spend), active offers and milestone proximity.
   - This is the improved version of the old offer-preview flow, and it needs **no sync fan-out**.
3. SSE:
   - `POST /v1/recommendations/stream-ticket` → a one-time 60-second ticket stored in Redis;
   - `GET /v1/recommendations/stream?ticket=` (works for browser EventSource);
   - fan-out via **Redis pub/sub** channel `reco:acct:{id}`, so it works across N instances;
   - heartbeat every 25s; reconnect with `Last-Event-ID` replays the latest result.

**Tests:**
- Optimizer golden cases vs brute force on small inputs.
- Best-card honours an exhausted cap.
- SSE across 2 instances (two app contexts + a shared Redis).
- A ticket can be used once only.

**Trail:** `p15-wallet-sse.html` — live stream panel showing pushes.

**DoD:**
- [ ] IT-GREEN.

---

### P16 — Billing: plans, subscriptions, entitlements, payments, webhooks

**Schema (`billing`)**
- `plans (id, code UNIQUE CHECK(FREE,PREMIUM,VIP), price_paise, interval CHECK(month,year), entitlements jsonb, active)`.
- `subscriptions (id, account_id, plan_id, status CHECK(trialing,active,past_due,canceled,expired), current_period_start, current_period_end, cancel_at_period_end, provider, provider_sub_id UNIQUE, version)`
  plus a partial `UNIQUE(account_id) WHERE status IN ('trialing','active','past_due')`.
- `payments (id, account_id, subscription_id, amount_paise, status, provider_payment_id UNIQUE, created_at)`.
- `invoices` + `invoice_lines` (GST 18% split CGST/SGST vs IGST by state — mock tax logic, documented).
- `webhook_events (provider, provider_event_id, received_at, processed_at, PRIMARY KEY(provider, provider_event_id))`.
- `entitlement_overrides (account_id, key, value, expires_at)` — perks and ad holidays.

**Provider adapter interface** + `MockPaymentProvider`, which simulates success, failure and delayed
webhooks with HMAC signatures. Uses **Mode B idempotency** for checkout.

**Endpoints**
- `GET /v1/plans`.
- `POST /v1/subscriptions/checkout` (Idem: REQ, step-up).
- `GET /v1/subscriptions/me`, `POST /v1/subscriptions/me:cancel`.
- `GET /v1/entitlements/me` (cached 60s; also embedded as the `tier` claim when the token refreshes).
- `POST /v1/webhooks/{provider}` (signature, replay-safe).
- `GET /v1/invoices` (keyset).

**Events:** `billing.subscription.changed`, `billing.entitlements.changed`.

**Tests:**
- Webhook replay → a single effect.
- Out-of-order webhooks (succeeded before created).
- Mode B crash recovery (kill after the provider call, before completion) → the sweeper resolves it.
- Only one active subscription can exist.

**Trail:** `p16-billing.html`.

**DoD:**
- [ ] IT-GREEN.

---

### P17 — Commerce: marketplace deals, affiliate tracking, card applications, ads

**Schema (`commerce`)**
- `deals (id, merchant_id, category_path, components jsonb[] (value+cap each), eligible_card_products uuid[], valid tstzrange, status, priority)`.
- `deal_clicks (id, created_at, account_id NULL, deal_id, click_token_hash, ip_hash, ua_hash)` — partitioned daily.
- `conversions (id, click_id, provider_ref UNIQUE, amount_paise, commission_paise, status)` — idempotent postbacks.
- `deal_click_counters (deal_id, day, clicks, uniques)` — flushed from Redis every 10s.
- `card_applications`:
  - `id`, `account_id`, `card_product_id`;
  - `status CHECK(submitted,in_review,approved,rejected,withdrawn)`;
  - `status_history jsonb[]`, `version`;
  - `UNIQUE(account_id, card_product_id) WHERE status IN ('submitted','in_review')`.
- Ads:
  - `campaigns`, `creatives`, `placements`;
  - `ad_impressions_raw` (partitioned) + counters;
  - frequency caps in Redis;
  - ad-free handled through entitlements.

**Logic**
- Savings: each component is capped separately (golden: ₹75k → ₹7,000).
- Feed ranking 80/20 (relevance/sponsor) + 60/40 (card-match/savings), with card-match as a
  **ranking branch, not a gate**.
- **Signed redirect** `GET /r/{token}` (HMAC, 7-day expiry) → logs the click → 302.
- Weighted ad rotation.

**Endpoints**
- `/v1/deals` (keyset feed, personalized).
- `/v1/deals/{id}`.
- `/r/{token}`.
- `POST /v1/webhooks/affiliate/{network}`.
- `/v1/card-applications` (Idem: REQ, keyset, withdraw).
- Admin: decide, stats.
- `GET /v1/ads/serve?placement=`.
- Admin campaign CRUD.

**Tests:**
- Savings golden.
- Postback replay.
- Counter flush correctness under concurrency.
- Frequency cap.
- Duplicate application blocked.

**Trail:** `p17-commerce.html`.

**DoD:**
- [ ] IT-GREEN.

---

### P18 — Audit, notifications, privacy (export/delete saga)

**Schema (`audit`)**
- `audit_log`:
  - `id`, `occurred_at`, `actor_type`, `actor_id`, `action`, `target_type`, `target_id`;
  - `ip_hash`, `request_id`, `details jsonb`, `prev_hash bytea`, `hash bytea`;
  - partitioned monthly, BRIN on `occurred_at`;
  - `REVOKE UPDATE, DELETE`.
  - Single-writer chain under an advisory lock.
  - Daily **anchor hash** exported to object storage.
- `activity_events` — partitioned daily, 90-day retention.
- `activity_hourly` — aggregates for the admin dashboard.
- `notifications (id, account_id, channel CHECK(email,push,inapp), template, payload, status, attempts, next_attempt_at, created_at)` — keyset in-app inbox.
- `notification_prefs`.
- `privacy_requests (id, account_id, kind CHECK(export,delete), status, steps jsonb, created_at, completed_at)`.

**Consumers:** security alerts, budget thresholds, subscription changes, application status updates.

**Delete saga**
1. identity marks the account `deletion_pending` and revokes its sessions.
2. Each service deletes or anonymizes that account's rows and acknowledges via event.
3. identity destroys the account's DEK (crypto-shred).
4. audit keeps a pseudonymized record.
5. Timeout and retry handling; the saga is resumable.

**Export:** each service writes a JSON part to object storage; they are zipped and served through a
presigned link with a 24h expiry.

**Endpoints**
- `GET /v1/notifications` (keyset), `:read`, `/v1/notification-prefs`.
- `POST /v1/privacy/export` and `POST /v1/privacy/delete` (both step-up, Idem: REQ).
- Admin: `/v1/admin/audit` (keyset + filters), `/v1/admin/audit:verify-chain`, `/v1/admin/activity/dashboard`.

**Tests:**
- A tampered row breaks chain verification.
- The saga completes across all services (Testcontainers, multi-service slim run) and each service
  holds no rows for the account afterwards.
- Notification retry with backoff.

**Trail:** `p18-audit-privacy.html`.

**DoD:**
- [ ] IT-GREEN.

---

### P19 — Observability, hardening, scale proof, trail removal, release

**Steps**
1. OTel across all services; Grafana dashboards (RED metrics per service, DB pool, Kafka lag, outbox lag, SSE connections).
   Alerts: error rate, p99, outbox lag > 5s, DLQ non-empty.
2. Vault: KV for secrets + Transit for the KEK. The Consul decision is recorded in an ADR
   (likely dropped — compose/K8s DNS is enough).
3. Security:
   - OWASP ZAP baseline against the gateway;
   - dependency and image scans are clean or waived in an ADR;
   - mTLS option documented;
   - pen-test checklist in `docs/security/`.
4. **Scale proof:**
   - `seed.sh large`;
   - k6 scenarios (browse with the 50/25/24 pattern, write bursts with idempotency retries, reco reads, SSE soak 30 minutes);
   - record results against the §8.1 targets in `docs/perf/results.md`;
   - fix hotspots.
5. Chaos: kill Kafka for 60s (the outbox catches up), kill the relay leader (failover), a Postgres
   restart (pool recovery), and Redis down (rate limiter fails open for reads and closed for auth).
6. Runbooks: deploy, rollback, key rotation, partition maintenance, DLQ replay, restore from backup.
7. **Remove the trail:**
   - delete `test-ui/`, the `trail` compose profile and dev CORS origins;
   - verify that the `/_dev/**` endpoints are excluded from the prod profile;
   - CI guard: the release branch fails if `test-ui/` exists or the `dev` profile beans are present in the prod context.
8. Partitioning playbook for `expenses`/`card_transactions` (> 500M rows), documented and not executed.
9. Release checklist + `v2.0.0` tag instructions.

**DoD:**
- [ ] Targets met (or gaps documented with a plan).
- [ ] Chaos tests pass.
- [ ] Trail removed.
- [ ] `verify.sh full` green on the release branch.

---

## Appendix A — `BUILD_README.md` template (created in P00, updated every response)

````markdown
# Dexter v2 — Build README (living handoff)

**Last updated:** <date> · **Chat/phase:** P<NN> r<k> · **Model:** <model>
**Next chat starts with:** P<NN> step <x> | P<NN+1> (after verifying P<NN>)

## 1. Phase status
| Phase | Name | Status (WRITTEN/COMPILED/UNIT-GREEN/IT-GREEN/DONE) | Evidence (verify-report date) | Notes |
|---|---|---|---|---|
| P00 | Bootstrap | ... | ... | ... |
... (P00–P19)

## 2. In progress (exact stopping point)
- Last completed step:
- Files touched this response:
- Next concrete action:

## 3. Verification of previous phase
- Checklist results (item → pass/fail → fix commit/file)
- Issues found and fixed:

## 4. What exists now
- Modules and their responsibilities (1 line each)
- Endpoints table (service, method, path, auth, idempotency, paging) — or link to docs/api/routes.md
- Events table (topic, producer, consumers, schema version)
- Query→Index tables (per service, link)

## 5. Decisions (ADR index)
| ADR | Title | Status |
|---|---|---|

## 6. Plan amendments (deviations from MASTER_PLAN.md)
| Date | Phase | Change | Reason | Affects later phases |
|---|---|---|---|---|

## 7. Known gaps / tech debt (honest, never deleted silently)
- ...

## 8. How to run & verify (Codespaces)
```bash
cp .env.example .env
./scripts/run.sh core            # infra up
./scripts/seed.sh small
./scripts/verify.sh full         # writes verify-report.md → upload it to the next chat
docker compose -f infra/compose/docker-compose.yml --profile trail up -d   # http://localhost:8099
```

## 9. Demo accounts (dev only)
- asha@demo.in / ravi@demo.in / admin@demo.in — passwords in .env.example

## 10. Changelog (one line per response)
- <date> P<NN> r<k>: ...
````

---

## Appendix B — Phase-to-chat quick reference

| Chat | Phase | Needs from previous |
|---|---|---|
| 1 | P00 Bootstrap | old zip, this plan |
| 2 | P01 Core A | + new zip, BUILD_README, verify-report |
| 3 | P02 Core B | same |
| 4 | P03 Harness/seed/trail | same |
| 5 | P04 Identity A | same |
| 6 | P05 Identity B | same |
| 7 | P06 Gateway | same |
| 8 | P07 Ledger A (reference impl) | same |
| 9 | P08 Ledger B | same |
| 10 | P09 Catalog A | same |
| 11 | P10 Catalog B | same |
| 12 | P11 Portfolio A | same |
| 13 | P12 Portfolio B + calc + parser | same |
| 14 | P13 Planning | same |
| 15 | P14 Reco A | same |
| 16 | P15 Reco B | same |
| 17 | P16 Billing | same |
| 18 | P17 Commerce | same |
| 19 | P18 Audit/Notify/Privacy | same |
| 20 | P19 Hardening & release | same |

If a phase grows too large for one chat, split it as `P07a` / `P07b` and record that in Plan Amendments.
Never merge two phases into one chat.
