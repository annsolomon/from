# Dexter v2 — Build README (living handoff)

**Last updated:** 2026-10-04 · **Chat/phase:** P02b r11 · **Model:** Claude Opus 5.5
**All of P02 is written.** Next: run `./scripts/verify.sh full` **and** `./scripts/trail-check.sh` on r11 in `/workspaces/d2b`
(§8), open `p02-libs.html` in Live mode, and paste both reports. P02 becomes **DONE** when both pass; the next chat then
fixes whatever failed, or verifies P02 and starts **P03**.
r11 = r10 (`dexter-cache` + Redis ACLs, `ArchRules`, demo on cache) + the P02 trail: `libs/dexter-lab` (`/_dev/p02/*` in ledger),
`test-ui/p02-libs.html`, api-smoke P02 block, trail-check P02 checks. Status: **WRITTEN** (type-checked, not run by Maven).

## 1. Phase status

| Phase | Name | Status (WRITTEN/COMPILED/UNIT-GREEN/IT-GREEN/DONE) | Evidence (verify-report date) | Notes |
|---|---|---|---|---|
| P00 | Bootstrap, toolchain, infra | **DONE** | `verify.sh full` PASS 17/17 (run 7, 2026-09-21) + `trail-check.sh` PASS 12/12 (r12, 2026-09-21) | |
| P01 | Core libs A (core/db/web) | **DONE** | `verify.sh full` 17/17 PASS (2026-09-22; re-run 2026-09-27 git 0fcfed1: core 28 unit, db 20 unit + 30 IT, web 12 unit, 10 service smoke); trail-check 15/15 PASS | IT-GREEN 2026-09-22 |
| P02 | Core libs B (idempotency/events/security/test) | **P02a IT-GREEN** · P02b security + demo-on-JWT **IT-GREEN** (2026-10-04) · cache + ACL + ArchRules + demo-on-cache + p02 trail/lab **WRITTEN** (r10/r11) | P02a r4: `verify.sh full` **PASS 17/17** in Codespaces (2026-09-27): maven (unit + failsafe ITs incl. Testcontainers PG17 + Kafka, convergence enforced), 10 services UP, api-smoke PASS, k6 thresholds met | Split P02a/P02b (A-13). P02 is DONE when P02b is IT-GREEN + p02 trail check. Per-module test counts: read from the r4 `verify-report.md` in the P02b chat. |
| P03 | Harness, seed, trail kit | NOT STARTED | — | |
| P04 | Identity A | NOT STARTED | — | |
| P05 | Identity B (MFA/admin/RBAC) | NOT STARTED | — | |
| P06 | Gateway | NOT STARTED | — | |
| P07 | Ledger A (reference impl) | NOT STARTED | — | |
| P08 | Ledger B | NOT STARTED | — | |
| P09 | Catalog A | NOT STARTED | — | |
| P10 | Catalog B (ingestion) | NOT STARTED | — | |
| P11 | Portfolio A | NOT STARTED | — | |
| P12 | Portfolio B (calc + parser) | NOT STARTED | — | |
| P13 | Planning | NOT STARTED | — | |
| P14 | Reco A | NOT STARTED | — | |
| P15 | Reco B (optimizer/SSE) | NOT STARTED | — | |
| P16 | Billing | NOT STARTED | — | |
| P17 | Commerce | NOT STARTED | — | |
| P18 | Audit/notify/privacy | NOT STARTED | — | |
| P19 | Hardening & release | NOT STARTED | — | |

## 2. In progress (exact stopping point)

- **r10 (2026-10-04) stopped after:** `dexter-cache` (main + 3 unit test classes), Redis ACL infra + verify checks, `ArchRules` +
  fixtures + `ArchRulesTest`, `TcpProxy`, `TestRedis` ACL kit, `CacheContract` + `LoadGate`, demo on cache
  (`GET /demo/notes/{id}`, cached list, Path A in `NoteService`, Path B `NoteCacheInvalidation`), ITs `TenantCacheIT` (12),
  `RedisAclIT` (2), `DemoCacheContractIT` (9), `DemoCacheInvalidationIT` (2). Docs: ADR-0004 §12, errors.md, runbook `redis-acl.md`.
  **Sandbox evidence only (nothing run by Maven):**
  - javac 25.0.1 type-check of every main + test file in `libs/` and `contracts/` against Spring Framework 7.0.9, Boot 4.0.8,
    Spring Data Redis/Commons 4.0.7, Lettuce 6.8.2, ArchUnit 1.4.2, Micrometer 1.16.7, Jackson 3.1.5, JUnit 6.0.3, AssertJ 3.27.7,
    Kafka 4.1.0, servlet 6.1 sources: **0 errors in r10 files** except references to jars without sources here
    (Testcontainers, Hikari, pgjdbc, Nimbus — unchanged code paths). One real API error was found and fixed
    (`containAnyMethodsThat` is a `JavaClass.Predicates` predicate, not a `ClassesShould` method).
  - The five Lua scripts and the ACL template ran on a real Redis 7.0.15: every cache operation works as the service user;
    foreign keys, `KEYS`, `FLUSHALL`, `CONFIG GET`, `DEL` and scripts reaching foreign keys are refused.
  - `CacheKeys`/`CacheValue`/`CacheScope` assertions ran (harness): key formats, canonical params, codec edge cases.
  - ArchUnit 1.4.2 reads Java 25 class files (1.4.1 added major 69; release notes checked).
- **r11 (2026-10-04) completed P02:** `libs/dexter-lab` (dev-only P02 lab in ledger, A-23) + `test-ui/p02-libs.html` (Mock + Live)
  + `scripts/lib/p02_smoke.py` (api-smoke P02 block, 36 checks) + trail-check P02 checks + `ledger.http` entries + infra
  (database `lab` + roles `lab_*`, Redis user `lab`, always added by the init scripts; verify infra-checks adjusted).
  Sandbox evidence: javac 25 type-check of every file in `libs/` + `contracts/` (incl. `dexter-lab` main + tests) — 0 errors
  except references to jars without sources (Nimbus, pgjdbc, Hikari, Testcontainers, logback, uuid-creator); the trail
  page script parses (node 22); `p02_smoke.py` ran green against a fake lab (logic only, not the Java lab).
  **Nothing of P02b r10/r11 has been run by Maven or against real infra yet.**
- **P02 is complete in code.** Remaining = evidence: `verify.sh full` (unit + ITs + api-smoke P02 block) and `trail-check.sh`.
- **What to look for in the r10 report** (first Maven build of r10): enforcer convergence with spring-data-redis/lettuce/
  netty/reactor/archunit; `DexterCacheAutoConfigurationTest`; Lettuce handshake as the ACL user (`client|setinfo` is in the
  rule); `ArchRulesTest` (13) on the demo; `RedisAclIT` "no NOPERM swallowed"; timing-sensitive outage tests
  (`TenantCacheIT.redisDownBypassesOnceThenTheBreakerSkipsRedis`, `DemoCacheInvalidationIT.aRedisOutageDuringPathBIsRetriedNotLost`).

- **P02a is IT-GREEN** (r4 run, `verify.sh full` 17/17 PASS, 2026-09-27). History: r3 run failed at `dexter-events` (test bug: `EnvelopeCodecTest.roundTrip` compared `LongNode` vs `IntNode`); fixed in r4.
- **P02b r6 stopped after:** `dexter-security` complete (main + tests), `TestJwt` kit, security tests in `dexter-test`, demo switched from `X-Demo-Account` to the verified token (`Tenant` argument) + `DemoAuthIT`, ADR-0007, errors.md security section, A-17..A-19.
  Sandbox evidence only: javac 25 type-check of all new/changed main and test files against Spring Framework 7.0.9, Boot 4.0.8, Jackson 3.1.5, servlet 6.1, JUnit 6.0.3, AssertJ 3.27.7 sources: **0 errors in our files** apart from third-party jars that have no GitHub sources (uuid-creator, Hikari, pgjdbc, Testcontainers).
  Caveat: Nimbus 10.x source is not on GitHub, so Nimbus calls were checked against the 5.x API mirror (same signatures for every call used). Nothing was run.
- **Codespaces run 2026-09-27 (repo `/workspaces/p02`, code in `dexter-v2/`):** 16/17 PASS — static, infra, `full.maven` PASS, 10 services UP, api-smoke PASS; `full.k6-smoke` FAIL (k6 not installed).
  **The `unzip -o ~/dexter-v2-P02-r6.zip` in that run failed (file not in `~`)**, so the run tested whatever code was already in the folder.
  If that was not r6, `dexter-security` has still never been compiled by Maven. The report was not uploaded, so this is unconfirmed:
  P02b stays **WRITTEN** until a report shows the security tests. Quick check in the Codespace:
  `ls libs/dexter-security/src/main/java/in/dexter/security/jwt` (must list `JwtVerifier.java`).
- **P02b security IT-GREEN — `verify.sh full` 17/17 PASS, 2026-10-04, repo `/workspaces/d2b`, Java 25.0.4** (run on r7 code with python3 installed by hand; r8 changed only scripts/devcontainer/README).
  Report rows: `libs/dexter-security` unit 11/0/0/0, `libs/dexter-test` unit 35/0/0/0, IT 40/0/0/0. From `target/verify/mvn.log`:
  `DexterSecurityAutoConfigurationTest` 4, `AuthContextTest` 5, `BearerTokensTest` 2, `ServiceTokenClientTest` 5, `JwtVerifierTest` 20,
  `SecurityInterceptorTest` 10, `DemoAuthIT` 5 — all 0 failures/errors. Nimbus 10.9.1 compiled and behaved as the 5.x API check predicted.
- **Codespaces run 2026-10-04 (first) on r7 (repo `/workspaces/d2b`, code at the root, devcontainer applied, Java 25.0.4):** `full.maven` **PASS** — first Maven build of `dexter-security` (Nimbus 10.9.1) and the JWT demo; infra, 10 services UP, k6 PASS. FAIL: `static.rls-gate`, `static.poms-wellformed`, `full.api-smoke`, report writer — all `python3: command not found` (the devcontainer base image has no Python). `static.skeletons` passed falsely for the same reason (empty module list). No report file, so per-test rows are unconfirmed.
- **r8 fix:** devcontainer adds the Python feature; `verify.sh` installs python3 via apt when missing and stops if it can't; `static.skeletons` now fails on an empty module list.
- **r7 fix:** `verify.sh full` installs k6 itself when it is missing (`scripts/install-tools.sh`, falls back to `~/.local/bin`, no sudo; log in `target/verify/k6-install.log`). The k6 step failed only because the devcontainer (which installs k6) is not at the repo root.
- **Not started in P02b:** `dexter-cache` (ADR-0004) + generation keys + invalidation consumer base + cache contract, Redis ACLs, `ArchRules` (pin archunit 1.4.2, core artifact only), demo on cache, `p02-*.html` + lab endpoints (idempotency replay/409/422, outbox → Kafka → projection, DLQ, JWT playground).
- **P02b scope (A-13):** `dexter-security` (resource server, JWKS cache + unknown-kid refetch, `AuthContext`, audience check, `@RequiresStepUp`, `@RequiresPermission`, service-token client, test JWKS/JWT factory; pins nimbus + bouncycastle, verified on Maven Central), `dexter-cache` (ADR-0004, generation keys, Redis ACLs, cache contract), `ArchRules` (pins archunit), demo module switched from the `X-Demo-Account` test header to `AuthContext`, `p02-*.html` trail page + lab endpoints (idempotency replay/409/422, outbox → Kafka → projection, DLQ).

## 3. Verification of previous phase

### P02a re-check in the P02b chat (2026-09-27)

`verify-report.md` of the r4 run was **not uploaded** into the P02b chat, so the per-module rows could not be read (§0.3 step 1 open).
P02a stays IT-GREEN on the r5 README record (17/17 PASS). The next report (this zip) re-runs every P02a IT anyway; check the
`dexter-test`, `dexter-events`, `dexter-idempotency`, `dexter-contracts` rows there. No P02a code issue found while reading
the demo for the switch.

### P01 re-verified in the P02 chat (verify-report 2026-09-27 09:57Z, git 0fcfed1, PASS 17/17)

Every P01 DoD item still holds (maven, 10 services UP, api-smoke P01 block, k6 p95 14 ms / 0 failures). One README correction:
the P01 evidence row said "db 22 unit, web 3"; the report counts **db 20 unit, web 12 unit** (the old row counted classes, not tests). Fixed above. No code issues found.

### P01 against its DoD (r4 run, 2026-09-22)

| DoD item | Result | Evidence |
|---|---|---|
| Unit + IT green | PASS | `full.maven` PASS: core 28, db 22 unit, db 30 IT, web 3, 10 service smoke tests |
| RLS context never leaks on a pooled connection | PASS | `TenantTxIT` (pool size 1): context empty after commit and after rollback, 0 rows without context, WITH CHECK blocks a foreign `account_id` |
| Keyset paging correct over duplicate keys | PASS | `KeysetPagerIT`: 1,000-row walk, every id once, strict `(occurred_at, id)` DESC order, mid-walk inserts cause no duplicates, tampered/filter-mismatched cursors rejected |
| Index-only plans, no Seq Scan / Sort | PASS | `PlanAssertIT` + `KeysetPagerIT.planUsesTheIndexAndNoSort` |
| Every service answers with request id + coded ProblemDetail | PASS | `api-smoke` P01 block over 9 servlet services; 10 service smoke tests |
| Trail page exercised | PASS | `trail-check` 15/15 including `p01-lab` and `p01-problem`; the lab's keyset walk also runs in api-smoke |
| k6 smoke | PASS | thresholds met |

### P00 against its DoD and `verify-report.md` (2026-09-21 17:25Z, git fd2f057):

| DoD item | Result | Evidence |
|---|---|---|
| `verify.sh full` green | PASS | 17/17 steps PASS |
| All containers healthy | PASS | 5/5 core containers healthy, infra checks OK (9 DBs, 45 roles, 0 bypass roles, cross-DB isolation) |
| Every service `/actuator/health` UP | PASS | services-up 10/10, api-smoke PASS, k6 p95 14 ms, 0 failures |
| README created | PASS | this file |
| Trail exercised | PASS | `trail-check.sh` 12/12 (r12) |

Issues found in P00 code and fixed in P01:
1. `run.sh services` reused `target/app.jar` whenever it existed, so after `unzip -o` it could start the previous phase's code (`verify.sh full` was safe because it runs `mvn verify` first; `trail-check.sh` was not). Now it rebuilds when any source or POM is newer than a jar.
2. Services ran with no profile, so dev-only endpoints could not be exercised. `run.sh` now exports `SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-dev}`.

The detailed P00 run history (runs 1–7, MinIO removal, nginx Host fix) is summarised in the changelog (§10).

Issues found by the r3 Codespaces run (`full.maven` FAIL, 2 of 48 tests) and fixed in r4:
1. **`KeysetPagerIT` order assertion** failed at a random row each run. Postgres orders `uuid` as 16 **unsigned** bytes, while `UUID.compareTo` compares two **signed** longs, so the two disagree for most random UUIDs. The pager itself was right (the comparison happens in SQL), but the in-memory mirror of that order was not. Added `in.dexter.core.id.Uuids.POSTGRES_ORDER` with its own unit test, and used it in the IT and in the lab controller's in-memory paging.
2. **`TenantTxIT` RLS assertion**: Postgres reports a WITH CHECK violation with SQLSTATE 42501, which Spring translates to `BadSqlGrammarException`, so `row-level security` is on the cause, not the top-level message. The RLS policy did fire — the test now asserts `DataAccessException` plus the cause text.
3. Removed a stray directory literally named `{money,id,log,tenant,validation,request}` and the checked-in `__pycache__`.

Issues found in P01's own code during self-review and fixed:
1. Cursor malleability: the last signature character carries unused base64 padding bits, so changing it produced a second accepted spelling of the same cursor. The codec now requires canonical base64 for payload and signature; a unit test tampers every single character.
2. `KeysetQuery` rejected base SQL whose `WHERE` starts a new line (text blocks). The check is now a word-boundary regex, with a test.
3. The root POM had the JaCoCo plugin twice; one block removed.
4. The web test asserted an exact `Content-Type`; now a compatible-type check (a charset suffix must not fail it).

## 4. What exists now

**P02b r11: dev-only P02 lab (WRITTEN, type-checked)** — deleted in P19 with `test-ui/`

| Piece | What it does |
|---|---|
| `libs/dexter-lab` (`in.dexter.lab.p02`) | `P02LabAutoConfiguration` (only `dev` profile **and** `dexter.lab.p02.enabled=true`; builds beans without touching infra), `P02Lab` (lazy start: templates + lab tables in database `lab` as `lab_owner`, `TenantTx`/idempotency/outbox/inbox on `lab_app`, `OutboxRelay` on `lab_relay`, topics `dexter.lab.note.v1` + consumers `projector` / `cache-inv` with retry-2s/retry-5s/DLQ, `DexterCache` as Redis user `lab` through `LabProxy`; failure → 503, next call retries), `LabIssuer` (ES256 keys, rotation/retirement, JWKS fetch counter, 12 token defects), `LabProjector` (inbox + version guard; title `always-fails` → DLQ), `P02LabController`. Tests: `LabIssuerTest` (every defect → its reason with the real verifier; rotation/retirement), `P02LabAutoConfigurationTest` (profile/property guard, no I/O at startup) |
| Endpoints (ledger, dev) | `GET /_dev/p02/status[?init=true]`; `POST /jwt/token`, `GET /jwt/whoami`, `POST /jwt/rotate`, `POST /jwt/retire`, `GET /jwt/jwks`; `POST /notes[?holdMs]` (Idempotency-Key required), `PUT /notes/{id}`, `GET /notes`, `GET /notes/{id}` (`X-Dexter-Cache: miss/hit/bypass`); `GET /cache`, `POST /cache/bump`, `/cache/purge`, `/cache/redis/cut`, `/cache/redis/restore`; `GET /outbox`, `/projection`, `/dlq` — all under `/_dev/p02` |
| `test-ui/p02-libs.html` | Persona + token, JWT playground, Idempotency tester (replay/422/409/400), outbox/projection/DLQ viewer with auto-refresh, cache panel (read/list/rename/bump/cut/restore/purge + generation/keys/metrics). Mock mode simulates all of it |
| Smoke / trail | `api-smoke.sh` → `scripts/lib/p02_smoke.py` (36 checks against ledger :8082); `trail-check.sh` → page + lab start + token/whoami + idempotent POST/replay through the nginx proxy |
| Infra | `apply-roles.sh` always adds database/roles `lab` (dev); `render-acl.sh` always adds Redis user `lab`; verify infra-checks count service roles without `lab_*`, check the `lab` database, and expect the extra Redis user |

**Query→Index (P02 lab, dev only)**

| Where | SQL shape | Index |
|---|---|---|
| Lab list | `WHERE account_id = app_account() ORDER BY created_at DESC, id DESC LIMIT n+1` | `notes_list` |
| Lab detail / rename | `WHERE account_id = :a AND id = :id` | `notes_pkey` |
| Lab projection view | `WHERE account_id = app_account() ORDER BY note_id LIMIT 50` | `note_titles_pkey` |
| Lab outbox viewer (relay role) | `WHERE message_key = :k ORDER BY created_at DESC LIMIT 20` | `outbox_lab_key` (partitioned) |

**P02b r10 (WRITTEN, type-checked)**

| Module | Responsibility |
|---|---|
| `dexter-cache` | `CacheScope` (tenant/global, TTL ≤ 1 h, schema version), `CacheKeys` (ADR-0004 §2 keys, `{account}` hash tag, canonical params SHA-256/16), `TenantCache.find/get` and `GlobalCache` (read-through; role in the params hash), `CacheEngine` (one-script read, in-JVM + Redis single flight with re-read, TTL ±10 % jitter, XFetch early refresh, NOT_FOUND 30 s, 256 KB cap, corrupt entry = miss, bypass + 1 s breaker, rejects reads inside a transaction), `RedisCacheStore` (Lua only), `CacheInvalidator` (Path A after commit — logged/counted on failure; Path B `bump` — throws `CacheUnavailableException`; `purgeAccount`), `InvalidationConsumer` (Path B base, `of(type → scopes)`), `CacheMetrics` (`cache.requests{result}`, `cache.loads`, `cache.invalidation.failures`; MeterBinder), `DexterCache.create`, auto-config (needs a `StringRedisTemplate`; `dexter.cache.*`) |
| `dexter-test` additions | `arch.ArchRules` (layers, pure domain, JDBC only in infra/config, no cache in api/domain, `CacheInvalidator` only from app/consumers, transactions only in app/consumers + no `@Transactional`, security annotations only in api, `@PublicEndpoint` never combined, no identity `@RequestHeader`/all-headers map, no raw `getHeader`); `net.TcpProxy` (cut/restore); `TestRedis` (ACL users from `infra/redis/acl.tmpl`, service-like Lettuce client: 200 ms, reject while disconnected, 100 ms reconnect; `keys(pattern)`); `contract.CacheContract` (9 cases) + `LoadGate`. Tests: `ArchRulesTest` (13, fixtures in `archfixture.bad`), `TenantCacheIT` (12), `RedisAclIT` (2), `DemoCacheContractIT` (9), `DemoCacheInvalidationIT` (2) |
| demo module | `config/CacheScopes.NOTES` (10 min); `app/NoteQueries` (cached detail + list, loader in its own `TenantTx.read`), `app/CachedNotePage`; `NoteService` bumps `NOTES` after commit on create/rename; `infra/NoteCacheInvalidation` (Path B); `NoteRepository.find`; `GET /demo/notes/{id}`; `DemoApp` connects as ACL user `demo` through a `TcpProxy` (breaker 200 ms); the pagination contract's seed bumps `NOTES` (it writes behind the use-cases) |
| infra | `infra/redis/acl.tmpl` + `render-acl.sh` (POSIX sh, runs in the container); compose Redis starts with `--aclfile /tmp/users.acl`; `.env.example`: `DEXTER_REDIS_USERS`, `REDIS_PW_SERVICE` (compose has defaults, old `.env` files keep working); `verify.sh` infra-checks: ACL user count + ledger user refused foreign keys/`KEYS`/`FLUSHALL`/`CONFIG GET`/`DEL`; pin `archunit` 1.4.2 |

**Cache scopes (demo)**

| Scope | Kind | TTL | Read paths | Bumped by |
|---|---|---|---|---|
| `notes` | tenant | 10 min | `GET /demo/notes/{id}` (incl. NOT_FOUND 30 s), `GET /demo/notes` (every page/filter) | Path A: `NoteService.create/rename`; Path B: `demo.note.created/renamed` |

**Query→Index (r10 additions)**

| Where | SQL shape | Index | Proof |
|---|---|---|---|
| Demo detail (cache loader) | `WHERE account_id = :a AND id = :id` | `notes_pkey` | PK lookup; `DemoCacheContractIT` |

**P02b library (IT-GREEN, 2026-10-04)**

| Module | Responsibility |
|---|---|
| `dexter-security` | `jwt.JwtVerifier` (ES256 only; rejects `none`/HMAC before key lookup; no `jwk`/`jku`/`x5u`/`x5c`; `typ` `at+jwt`; `iss` exact, `aud` ∋ service, `exp`/`nbf`/`iat` ±30 s, lifetime ≤ 15 min; required `sub`/`jti`/`client_id`/`sid`), `jwt.JwksCache` (5 min TTL, unknown-kid refetch, ≤ 1 fetch / 10 s, stale keys usable 1 h, then 503), `jwt.HttpJwksSource` (2 s/3 s timeouts, 64 KB cap, no redirects), `jwt.TokenClaims` (token contract, A-18); `AuthContext` (USER/ADMIN/SERVICE, `tenant()` = role USER, `tenantAs(role)`); `web.SecurityInterceptor` (default deny; `@PublicEndpoint`, `@RequiresPermission`, `@RequiresStepUp`, `@InternalEndpoint(callers)`; MDC `principal`); `web.AuthContextArgumentResolver` (`AuthContext`/`Tenant` params); `client.ServiceTokenClient` (client-credentials, per-audience cache, single flight); `SecurityErrors.STEP_UP_REQUIRED`; auto-config (`dexter.security.jwks-uri` → verifier; web wiring when a `JwtVerifier` bean exists; `dexter.security.client.token-uri` → token client) |
| `dexter-test` additions | `security.TestJwt` (ES256 keys, live JWKS source with fetch counter and outage switch, rotation/retire, token builder incl. `unsigned()`, `hs256WithPublicKey()`, `embedJwk`, `kid`, `typ`). Tests: `JwtVerifierTest` (20), `SecurityInterceptorTest` (10, MockMvc + real advice), `ServiceTokenClientTest` (5, JDK HttpServer), `DemoAuthIT` (5). Demo controller takes `Tenant` from the token; `DemoApp.bearer(account)` for the contract ITs |

**Security config (per service, from P04 on):** `dexter.security.jwks-uri`, `dexter.security.issuer`, optional `audience` (default `spring.application.name`), `step-up-max-age` 10m, `clock-skew` 30s, `max-token-lifetime` 15m, `jwks-ttl` 5m, `jwks-min-refetch-interval` 10s, `jwks-max-stale` 1h, `public-paths` [`/_dev/**`, `/error`]; `dexter.security.client.{token-uri,client-id,client-secret,refresh-skew}`. No service sets these yet (P04 identity issues tokens).

**P02a libraries (IT-GREEN, r4 run 2026-09-27)**

| Module | Responsibility |
|---|---|
| `dexter-idempotency` | `IdempotencyEngine` Mode A (`execute`: insert-or-wait on the PK, lock timeout → 409 + `Retry-After: 1`, fingerprint mismatch → 422, replay byte-identical because the first response is also read back from jsonb) and Mode B (`beginExternal` with lease/takeover, `recordRecoveryPoint`, `completeExternal`, `abandonExternal`); `IdempotentRequest` (sha256 of method, path, `CanonicalJson` body); `ApiResponse`/`IdempotentResponse`; `@IdempotencyKey(required)` resolver; `IdempotentResponses.toEntity` (`Idempotent-Replayed: true`); `IdempotencyPurgeJob` (24h, relay role); auto-config (resolver in servlet apps; engine when `dexter.db.schema` + JdbcClient) |
| `dexter-events` | `EventEnvelope` + `EnvelopeCodec` (strict; invalid = poison); `EventType`, `Topics` (ADR-0006 naming); `OutboxWriter` (fails without a read-write tx; app role INSERT only); `OutboxRelay` (session advisory-lock leader, LISTEN `<svc>_outbox_new`, 200ms poll, 500-row SKIP LOCKED batches, idempotent producer, mark after acks, partition maintenance + `OUTBOX_PARTITIONS_NOT_AHEAD` alert, maintenance tasks, test `Hooks`); `EventConsumer` (per-stage consumers, retry-1m → retry-10m → dlq, pause-until-due, poison → DLQ, commit after success/forward); `InboxGuard`; `ProjectionVersionGuard`; `InboxPurgeJob` (14 days); `TopicRegistry`; `KafkaClients`; auto-config (writer/inbox with `dexter.db.schema`; relay only with `dexter.events.relay.enabled=true`) |
| `dexter-contracts` (`contracts/`) | `events/envelope.v1.json`, `demo.note.created.v1.json`, `demo.note.renamed.v1.json`; records `NoteCreatedV1`, `NoteRenamedV1`; `SchemaContractTest` + `MiniSchema` (schemas and records in step; real envelopes validated) |
| `dexter-test` (main) | `TestPostgres` + `ServiceDb` (per-service DB with the infra role settings and all templates), `TestKafka` (apache/kafka:4.1.0), `TestRedis`; `RlsProofKit`; abstract `IdempotencyContract` (5.5, 6 cases) and `PaginationContract` (4.5, 9 cases); `HttpResult` |
| `dexter-test` (test): **demo template module** | `in.dexter.testkit.demo.{api,app,domain,infra,config}`: `demo.notes` + projections `note_stats`, `note_titles` (all FORCE RLS); POST (key required) and GET (keyset) over MockMvc; projector with inbox + version guard; caller = `Tenant` from the verified JWT (P02b). ITs: `DemoIdempotencyContractIT`, `DemoPaginationContractIT`, `DemoRlsIT`, `DemoAuthIT`, `OutboxRelayIT`, `ConsumerIT`, `IdempotencyEngineIT`, `RlsProofKitIT` |
| `dexter-db` additions | `maintenance.BatchPurge` (ctid batches, advisory xact lock, `BatchPurgeIT`); template `20-idempotency.sql` gains `recovery_point text` (Mode B) |
| `dexter-web` addition | `ProblemDetailsAdvice`: property `retryAfterSeconds` → `Retry-After` header |

**Query→Index (P02a)**

| Where | SQL shape | Index | Proof |
|---|---|---|---|
| Idempotency insert / load / complete | `... WHERE account_id = :a AND idem_key = :k` | `idempotency_keys_pkey` | `IdempotencyContract`, `IdempotencyEngineIT` |
| Idempotency purge | `ctid = ANY(ARRAY(SELECT ctid ... WHERE created_at < now()-24h LIMIT 5000))` | `idempotency_keys_created_brin` | `IdempotencyEngineIT` |
| Outbox claim | `WHERE published_at IS NULL ORDER BY created_at, id LIMIT 500 FOR UPDATE SKIP LOCKED` | `outbox_unpublished` (partial) | `OutboxRelayIT` |
| Outbox mark | `WHERE id = ANY(:ids) AND created_at BETWEEN :min AND :max` | outbox PK `(id, created_at)` + partition pruning | `OutboxRelayIT` |
| Inbox dedupe | `INSERT ... ON CONFLICT DO NOTHING` on `(consumer, event_id)` | `inbox_pkey` | `ConsumerIT`, `OutboxRelayIT` |
| Inbox purge | `received_at < now()-14d` in ctid batches | `inbox_received_brin` | `BatchPurgeIT` |
| Demo list | `WHERE account_id = app_account() [AND kind = :kind] ORDER BY created_at DESC, id DESC LIMIT n+1` | `notes_list` | `DemoPaginationContractIT.listQueryUsesTheIndex` (30k-row tenant; medium seed in P03) |
| Demo projection | `ON CONFLICT (account_id, note_id) DO UPDATE ... WHERE projected_version < EXCLUDED.projected_version` | `note_titles_pkey` | `ConsumerIT.anOlderVersionArrivingLateIsIgnored` |

**Events:** demo only: `demo.note.created` v1, `demo.note.renamed` v1 on `dexter.demo.note.v1`. No service produces events yet. No service wires the P02a libraries yet (P04/P07).

**P01 (unchanged)**

**P01 libraries**

| Module | Responsibility |
|---|---|
| `dexter-core` (no Spring) | `Money` (long paise, overflow-checked, `times(Bps)` HALF_EVEN, `allocate`), `Bps`, `Ids` (UUIDv7 via uuid-creator), `Tenant` (accountId, role, tz), `CoreErrors` + `DexterException` hierarchy, `Require` validation, `Clocks`/`MutableClock`, `PiiMasker`, `RequestIds` |
| `dexter-db` | `TenantTx` (transaction-local `set_config`, nesting rules, read → replica), `TenantScope` (ScopedValue guard), `DexterDataSources` (read-only routing), `Sql` binders (uuid[]/text[]/bigint[], ltree, jsonb, timestamptz), `Rows` + `RecordRowMapper`, `OptimisticUpdate` (412 vs 404), `KeysetCursorCodec` + `CursorKeyRing` (HMAC, kid rotation, filter binding), `KeysetQuery` + `KeysetPager` (first/next SQL variants, limit+1), `PartitionManager` (advisory-locked create-ahead/drop-older), `PlanAssert`, `SqlTemplates`; auto-config (`TenantTx` when a DataSource exists; codec/pager when `dexter.cursor.active-kid` is set) |
| `dexter-web` | `ProblemDetailsAdvice` (stable `code`, `requestId`, `errors[]`), `RequestIdFilter` (+ MDC), `StrictJsonCustomizer`, `ETags` + `@IfMatchVersion`, `PageRequest` argument resolver (limit 1..100, default 25), `PageResponse`, PII-masking logback converter + ECS JSON customizer, dev-only `P01LabController` (`/_dev/p01/*`) |

**SQL templates** (`libs/dexter-db/src/main/resources/sql-templates/`, `{{schema}}` placeholder): `00-base-functions` (schema, grants, `app_account()`, `app_actor_role()`), `10-partitions` (SECURITY DEFINER create/drop, relay-only), `20-idempotency` (table, BRIN, ENABLE+FORCE RLS, tenant policy, relay purge policies), `30-outbox` (daily partitions, unpublished partial index, NOTIFY trigger, insert-only for app), `40-inbox`.

**Endpoints:** still only actuator on every service, plus on the 9 servlet services under the `dev` profile only: `GET /_dev/p01/money`, `GET /_dev/p01/errors/{code}`, `POST /_dev/p01/strict-json`, `GET|PUT /_dev/p01/doc`, `GET /_dev/p01/items`, `POST /_dev/p01/cursor/encode|decode`, `GET /_dev/p01/mask`. Every servlet response carries `X-Request-Id`; every error is a coded ProblemDetail (catalogue: `docs/api/errors.md`). **Events:** none.

**Query→Index (P01, library level — proven by ITs on Testcontainers PG17)**

| Where | SQL shape | Index | Proof |
|---|---|---|---|
| `KeysetPager` first page | `… WHERE account_id = (SELECT demo.app_account()) ORDER BY occurred_at DESC, id DESC LIMIT :n+1` | `items_list (account_id, occurred_at DESC, id DESC)` | `KeysetPagerIT.planUsesTheIndexAndNoSort` (PlanAssert: no Seq Scan, no Sort, index used) |
| `KeysetPager` next page | `… AND (occurred_at, id) < (CAST(:k0 AS timestamptz), CAST(:k1 AS uuid)) ORDER BY … LIMIT :n+1` | same (row comparison is an Index Cond — checked on PG16 in the sandbox) | same test |
| `PlanAssertIT` keyset | `WHERE grp = :g AND id < :after ORDER BY id DESC LIMIT 26` | `plan_t_grp (grp, id DESC)` | `indexedKeysetQueryPasses` |
| Outbox claim (P02) | `WHERE published_at IS NULL ORDER BY created_at, id … SKIP LOCKED` | `outbox_unpublished (created_at, id) WHERE published_at IS NULL` | P02 |
| Idempotency purge (P02) | `created_at < now() - 24h` | `idempotency_keys_created_brin` (BRIN) | P02 |

**Tests written:** core 7 unit classes · db 5 unit classes (`KeysetCursorCodecTest`, `KeysetQueryTest`, `MiniJsonTest`, `SqlTest`, `DexterDbAutoConfigurationTest`) + 6 ITs (`TenantTxIT` pooled-connection leak test, `KeysetPagerIT`, `PlanAssertIT`, `PartitionManagerIT`, `SqlTemplatesIT`, `SqlBindingIT`) · web 3 (`WebLabMvcTest`, `DexterWebAutoConfigurationTest` dev-profile guard, `PiiLoggingTest`) · 9 service smoke tests extended.

**Trail:** `test-ui/p01-core.html` (Mock + Live): money rounding, ProblemDetail gallery, strict JSON, ETag/If-Match, signed cursor with kid rotation and filter binding, 50/25/24 window with a debug panel.

**Infra, scripts, ports:** unchanged from P00 except `run.sh` (stale-jar rebuild, dev profile), `api-smoke.sh` (P01 block), `trail-check.sh` (P01 page + lab through the proxy).

## 5. Decisions (ADR index)

| ADR | Title | Status |
|---|---|---|
| 0001 | Toolchain and platform versions (Java 25, Boot 4.0.8, Cloud 2025.1.3; upgrade to Boot 4.2 + Cloud 2026.0 before 2026-12-31) | Accepted: resolution + convergence proven in Codespaces 2026-09-21 |
| 0002 | Service map: 10 deployables + 1 Python | Accepted |
| 0003 | Object store: SeaweedFS instead of MinIO | Accepted: pull + health proven in Codespaces 2026-09-21 |
| 0004 | Redis cache invalidation: generation keys, after-commit + outbox-driven bumps, TTL bound | Accepted; built in P02b r10 (§12 implementation notes); proof pending the r10 run |
| 0005 | Replica routing via `LazyConnectionDataSourceProxy.setReadOnlyDataSource`; library-free cursor codec and PlanAssert (jsonpath) | Accepted: proven by the P01 run (2026-09-22) |
| 0006 | Events on plain kafka-clients; per-consumer retry/DLQ topics `<topic>.<consumer>.retry-1m`/`retry-10m`/`dlq` | Accepted: proven by the P02a r4 run (`OutboxRelayIT`, `ConsumerIT`, 2026-09-27) |
| 0007 | Request authentication: Nimbus ES256 verifier + JWKS cache + MVC `SecurityInterceptor` (default deny), no Spring Security filter chain | Accepted: proven by the 2026-10-04 run (`JwtVerifierTest`, `SecurityInterceptorTest`, `ServiceTokenClientTest`, `DemoAuthIT`) |

## 6. Plan amendments (deviations from MASTER_PLAN.md)

| Date | Phase | Change | Reason | Affects later phases |
|---|---|---|---|---|
| 2026-09-21 | P00 | A-01: `platform-bom` is the **parent** of the root POM (it inherits `spring-boot-starter-parent`) instead of an import-scoped BOM. | Maven 3.9 cannot import a BOM from the same reactor on a clean first build. | All modules inherit versions through root → platform-bom. |
| 2026-09-21 | P00 | A-02: third-party libraries that Boot does not manage (uuid-creator, nimbus, bouncycastle, resilience4j, archunit, wiremock, rest-assured) and plugins (jacoco, jib) are pinned in `platform-bom` **in the phase that first uses them**. | Each pin gets verified against Maven Central when it is added, not guessed in P00. | P01 pins uuid-creator + jacoco. P02 pins nimbus, bouncycastle, archunit, resilience4j. P03 pins rest-assured/wiremock. P19 pins jib. |
| 2026-09-21 | P00 | A-03: the fallback in 2.3 becomes "Java 21 on Boot 4.0.8", not "Boot 3.5.x". | Boot 3.5 reached end of life on 2026-06-30. | ADR-0001. |
| 2026-09-21 | P00 | A-04: `contracts/` and `seed/` are folders now and become Maven modules when they get content (P02/P04 and P03). `ai-parser/` is a README until P12. | Empty modules add no value. | P02, P03, P12 add the modules. |
| 2026-09-21 | P00 | A-05: trail Live mode calls same-origin `/api/*` (nginx → gateway :8080) instead of `localhost:8080` directly. | Avoids CORS config in services just for a throwaway page. | P03 trail kit and every trail page. |
| 2026-09-21 | P00 | A-07: the `core` profile uses SeaweedFS (`objectstore`) instead of `minio`. | MinIO community images were deleted from Docker Hub on 2026-09-11 (ADR-0003). | P12 (statement uploads) and P18 (exports) target the S3 API on :8333. |
| 2026-09-21 | P00 | A-08: new library `libs/dexter-cache` (ADR-0004), with generation-based Redis invalidation (after-commit bump + outbox-driven bump + TTL bound), tenant-scoped keys, a per-service Redis ACL, and a cache contract test suite. The skeleton was added in P00 r9. | MASTER_PLAN 8.2 defines cache-aside but not invalidation; without one design, every phase would invent its own, and stale or cross-tenant cache bugs follow. | **P02** builds `dexter-cache` + the invalidation consumer base + the Redis ACLs (split into P02a/P02b if it's too large, per Appendix B). P07/P08, P09/P10, P13, P14/P15, P16 and P18 declare cache scopes and TTLs and run the cache contract tests. |
| 2026-09-21 | P00 | A-06: the Trivy CI job is present but disabled (`if: false`). | Plan step 7 allows skipping until images exist. | P19 enables it, SHA-pinned. |
| 2026-09-22 | P01 | A-09: routing uses `LazyConnectionDataSourceProxy.setReadOnlyDataSource` instead of a custom `AbstractRoutingDataSource` (ADR-0005). | Built into Spring 6.1.2+; same behaviour, no custom code. | Services that add a replica (P19) use `DexterDataSources.readWriteRouting`; the replica pool must be read-only by default. |
| 2026-09-22 | P01 | A-10: `dexter-web` depends on `dexter-db` (`PageRequest`/`Page`/cursor types). `dexter-db` does not start anything without a DataSource. | One page type from SQL to JSON. | None. |
| 2026-09-22 | P01 | A-11: the Postgres test kit (`PgTestSupport`) lives in `dexter-db` test sources for P01. | `dexter-test` is a P02 deliverable. | P02 moves it into `dexter-test` as a shared kit. |
| 2026-09-27 | P02 | A-13: P02 is split. **P02a** = `dexter-idempotency`, `dexter-events`, `contracts`, `dexter-test` kit (containers, RlsProofKit, IdempotencyContract, PaginationContract) + demo module on db/web/idempotency/events. **P02b** = `dexter-security`, `dexter-cache` + Redis ACLs (A-08), `ArchRules`, demo on JWT + cache, p02 trail page. | P02 + A-08 is too large for one chat (Appendix B allows splits). | P02b follows once P02a is IT-GREEN; P03 starts after P02b. |
| 2026-09-27 | P02 | A-14 (revises A-11): the shared PG kit lives in `dexter-test` as `TestPostgres` + `ServiceDb` (any schema). `dexter-db` **keeps** its own `PgTestSupport` for its ITs. Library-level ITs for idempotency/events live in `dexter-test` next to the demo. | `dexter-test` depends on every library; a library using it in tests is a Maven reactor cycle (rejected regardless of scope). | Services' ITs use `dexter-test` normally (no cycle). |
| 2026-09-27 | P02 | A-15: `contracts/` is the Maven module `dexter-contracts`; schemas stay in `contracts/events/` and ship as `events/*.json`; records are hand-written and kept in step by `SchemaContractTest` (JSON Schema subset validator), not generated. | Plan allows either; avoids an unverifiable plugin pin and a Jackson 2/3 mismatch. | Every phase adding an event adds schema + record + a `SAMPLES` entry. |
| 2026-09-27 | P02 | A-16: event `data` carries timestamps as ISO-8601 strings, and the envelope is encoded field by field. | The wire form must not depend on a service's mapper date settings. | All event records. |
| 2026-09-27 | P02b | A-17: `dexter-security` enforces auth in a Spring MVC `HandlerInterceptor` with its own annotations, not the Spring Security filter chain / method security (ADR-0007). | Boot's security auto-config would lock actuator and the dev lab and bypass `ProblemDetailsAdvice`; custom annotations would need method-security proxies. | Authorization is at the controller boundary + admin RLS policies. P04 uses `spring-security-crypto` only (Argon2id). P06 gateway reuses `JwtVerifier`. |
| 2026-09-27 | P02b | A-18: access-token contract fixed now (`TokenClaims`): header `alg=ES256`, `typ=at+jwt`, `kid`; claims `iss`, `sub` (account UUID or `svc:<name>`), `aud` (list), `exp`, `iat`, `jti`, `client_id`, `sid` (users), `scope`, `roles`, `perms` (admins only), `amr`, `auth_time`, `mfa_enrolled`, `tz`, `tier`. ES256 instead of EdDSA. | MASTER_PLAN 7.1 lists claims but not `typ`, `client_id`, `perms` or MFA enrolment, which step-up needs; Nimbus EdDSA needs Google Tink. | P04 must issue exactly this; P05 fills `perms`; service tokens come from `/oauth2/token` (client-credentials, Basic auth, `audience` form param). |
| 2026-09-27 | P02b | A-19 (revises A-02): P02 pins only `nimbus-jose-jwt` 10.9.1 (now) and `archunit` (with ArchRules). BouncyCastle moves to P04 (Argon2id); Resilience4j to its first user (P06/P07). | ES256 needs no BouncyCastle; pin in the phase that first uses a library (A-02's own rule). | P04, P06/P07. |
| 2026-10-04 | P02b | A-20 (revises ADR-0004 §4): Path B `InvalidationConsumer`s bump without the inbox and throw on a Redis failure. | A duplicate bump costs one miss; "inbox committed, Redis unreachable" would lose the bump. Retry topics + DLQ + TTL bound it. | Every caching service's invalidation consumer (P07+). |
| 2026-10-04 | P02b | A-21 (revises ADR-0004 §9): `CacheInvalidator` may be called from app use-cases and from event consumers (`EventConsumer.Handler`); `ArchRules` enforces it. | ADR-0004 §4 itself has projections bump from their own transaction (infra). | P14/P15 projections. |
| 2026-10-04 | P02b | A-22: all dexter-cache Redis access is Lua scripts + SCAN/UNLINK; ACL = `eval evalsha get set incr pexpire pttl unlink scan publish subscribe ping info client\|setinfo client\|setname` on `<svc>:*`, `gen:<svc>:*`, `lock:<svc>:*`, `&cache:inv:<svc>`; one Redis user per service, default user for ops only. `ArchRules` lives in `dexter-test` (main) as plain rules (`ArchRules.check`), no `archunit-junit` engine. The demo adds `GET /demo/notes/{id}`. | Smallest command set; one template shared by compose and tests. | Each service sets `spring.data.redis.username/password/timeout=200ms` and adds an `ArchitectureTest` (P03 harness adds it to the servlet services). |
| 2026-10-04 | P02b | A-23: the P02 trail is backed by a dev-only module `libs/dexter-lab` hosted by ledger (`/_dev/p02/*`, `dev` profile + `dexter.lab.p02.enabled`), with its own Postgres database/roles `lab` and Redis user `lab` that the dev init scripts always create. It replaces MASTER_PLAN 11.2's `/_dev/outbox/recent` with `/_dev/p02/outbox|projection|dlq`. The lab mints its own tokens (issuer `https://identity.dexter.lab`, audience `lab`) and shows the verifier's rejection reason. | P02 has no service schema (A-12's reason); a separate database keeps the lab out of ledger's future Flyway history, and a separate module keeps the libraries free of lab code. | P19 deletes `libs/dexter-lab`, the ledger dependency, the `dev` block in ledger's `application.yml`, the `lab` additions in `apply-roles.sh`/`render-acl.sh`/`verify.sh`, and `test-ui/`. |
| 2026-09-22 | P01 | A-12: the P01 trail is backed by a dev-only lab controller in `dexter-web` (`/_dev/p01/*`, `@Profile("dev")`, guard test) instead of database-backed endpoints. | P01 has no service schema yet; the lab runs the real library code. Paging against a real table is P07. | P19 deletes it with the trail; `run.sh` defaults to the dev profile locally. |

## 7. Known gaps / tech debt (honest, never deleted silently)

- **Jackson tree equality is type-exact** (`IntNode(5)` ≠ `LongNode(5)`): never compare `JsonNode`s or envelopes built by `valueToTree` against parsed ones; compare encoded text or typed records.
- **Sandbox verification method (works, reuse it):** Maven Central is blocked, so the sandbox type-checks against dependency *sources* fetched from GitHub (javac 25, `-sourcepath`, flow analysis on) and runs pure-Jackson code on Jackson 3.1.5 compiled from source. It caught nothing Maven later disagreed with except runtime test semantics (the r3 `LongNode`/`IntNode` test bug).
- **Idempotency Mode B sweeper** (stale `in_progress` rows) is P16; the relay role's purge policy only exposes keys older than 24h, so P16 adds a sweeper policy.
- **Pre-auth idempotency scope** ("per client id") is P04: the engine takes a `Tenant`; P04 decides the synthetic tenant for registration.
- **Relay/consumer metrics** are logs only (`OUTBOX_PARTITIONS_NOT_AHEAD`, `OUTBOX_MAINTENANCE_FAILED`, DLQ forwards). Micrometer gauges/alerts arrive with P19.
- **traceparent** in events is null until P19 wires OpenTelemetry (`OutboxWriter` takes a supplier).
- **Relay sharding** (`hashtext(message_key) % N`) is documented only (MASTER_PLAN 6.3); built in P19 if needed.
- **No trail page for P02a**; the p02 trail page is still to come in P02b.
- **Security annotations are cached per handler at first request.** A handler with `@PublicEndpoint` plus a requirement fails on its first request, not at startup. ArchRules (P02b) should make that a build failure.
- **Step-up and permissions are enforced on controllers only** (A-17); app-layer code must not be reachable by other entry points without the same checks (Kafka consumers act as the system, not a user).
- **JWKS fetches are unmetered** (logs `JWKS_REFRESH_FAILED`, `AUTH_REJECTED reason=…`, `AUTH_JWKS_UNAVAILABLE`); Micrometer counters arrive in P19.
- **The P02 lab has no Testcontainers IT**; it is proven by `api-smoke` (P02 block) and `trail-check` against the real dev
  stack. Its SQL is not RLS-gate-checked by `verify.sh static` (the gate scans service migrations only); its tables have
  ENABLE + FORCE RLS and policies anyway.
- **Lab `X-Dexter-Cache`** is derived from counters around the call: a request that joined another request's in-flight load
  reports `hit`. Display only.
- **Existing dev volumes** made before r11 lack the `lab` database: `verify.sh full` drops volumes (fine); for manual runs
  use `./scripts/run.sh nuke` once, otherwise the lab answers 503 with that hint.
- **ArchRules are not yet run against the 10 services** (only the demo). P03 adds an `ArchitectureTest` per servlet service
  (the gateway is reactive and needs care: `dexter-test` brings servlet classes onto its test classpath).
- **Cache metrics** are Micrometer counters via a `MeterBinder` bean; no service exposes Prometheus yet (P19). Redis ops
  latency is not measured.
- **No L1 cache** for global data (ADR-0004 §5 optional). **No pub/sub** used yet although the ACL allows `cache:inv:<svc>`.
- **The cache loader runs in a virtual thread for early refresh**; a loader that needs request-scoped state (MDC, request id)
  will not have it there. Loaders must take everything from their arguments.
- **Redis Cluster** is not used; scripts touch undeclared keys of the same hash-tag slot (fine on Cluster, unproven).
- **Services do not use dexter-cache yet**; first users P07/P08.
- **No token revocation list:** a stolen access token lives up to 10 min (MASTER_PLAN 7.1 design). Session revoke-all (P05) works through refresh tokens only.

- **P01 is unverified by Maven.** The sandbox type-check used real library sources but not Maven resolution, annotation processing or dependency convergence. First risks to watch in the report: enforcer `dependencyConvergence` on Testcontainers 2.0.5 transitive deps, the logback `conversionRule` override of `%m`, and the Jackson 3 duplicate-key message match in `ProblemDetailsAdvice`.
- The console PII converter replaces logback's `%m`/`%msg`; if logback rejects the override, services fail to start (the smoke tests would show it). Fallback: a separate conversion word in `dexter-logback.xml`.
- `PlanAssert` on the **medium seed (≥1M rows)** is not possible until P03; P01 proves it on 51k rows.
- Keyset sorts must use one direction for all keys (row comparison). Mixed-direction sorts are rejected by design; none are planned.
- No default partition on partitioned tables: if `create_partitions_ahead` stops running for >3 days, outbox inserts fail. P02's relay job must alert on it.
- Anything that mirrors a database order in memory (page merges, dedupe, assertions) must use `Uuids.POSTGRES_ORDER`. There is no automated check that new code does; P03's pagination contract should add one.
- The lab cursor keys are hard-coded constants (dev-only). Real services configure `dexter.cursor.keys.*` from the environment (P04/P07).

- **Repo layout:** resolved in r8 — the code now sits at the root of its own repo (`/workspaces/d2b`), so `.devcontainer/` and `.github/workflows` apply. CI has not been checked yet.
  GitHub and Codespaces only read `.devcontainer/` and `.github/` at the repository root, so neither the devcontainer nor CI runs.
  The plan intends `dexter-v2` to be its own repo. Until that is decided, run `scripts/install-tools.sh` manually.
- **The sandbox still can't build.** To let the AI run `verify.sh unit` itself, allow-list `repo.maven.apache.org` and `repo1.maven.org` (MASTER_PLAN 0.6).
- **Kafka data is ephemeral in dev** (no volume), so topics vanish on container recreate. P02 revisits this when the topic registry creates topics at startup.
- **Container image tags not yet pulled successfully:** kafka 4.1.0 (core; confirmed by the next run), otel 0.130.0, tempo 2.8.1, prometheus v3.5.0, grafana 12.1.0, nginx 1.28-alpine. If a pull fails, bump the tag and note it here.
- **Object store:** SeaweedFS in dev runs with no S3 identities (anonymous, loopback-only). P12 must add the identities config and buckets before storing statements (ADR-0003).
- **Healthchecks:** otel-collector (distroless) and tempo have no container healthcheck. `run.sh status` probes them from the host (:13133, :3200/ready). They are only in the `obs` profile, which is not part of `full`.
- **Dependency convergence** may flag transitive conflicts on the first real resolve. The fix is explicit management in `platform-bom`, never a permanently disabled rule.
- **Spring Boot 4.0 OSS end of life is 2026-12-31.** The upgrade to Boot 4.2 + Cloud 2026.0 must be scheduled (ADR-0001).
- **Postgres 17 behaviour** was verified on PG16 in the sandbox. The Codespaces `infra-checks` step re-verifies on 17.
- Roles are created only on first volume init. Changing `.env` passwords requires `run.sh nuke` (see the runbook).
- CI actions are pinned by major tag. SHA pinning comes in P19 (§7.5).
- `seed.sh` is a stub (P03). The demo accounts don't exist yet.

## 8. How to run & verify (Codespaces)

```bash
# Unpack a new zip: upload it into the Codespace (drag into the Explorer), then use its real path.
cd /workspaces/d2b                              # repo root (code at the root since r8)
ls ./dexter-v2-P02-r*.zip                       # must list the uploaded zip; if not, find it: find /workspaces ~ -name 'dexter-v2-P02-*.zip'
unzip -q ./dexter-v2-P02-r11.zip -d /tmp/dv2 && cp -a /tmp/dv2/dexter-v2/. . && rm -rf /tmp/dv2 && mv ./dexter-v2-P02-r11.zip ~/
ls libs/dexter-cache/src/main/java/in/dexter/cache          # sanity: CacheEngine.java must be there
grep -q REDIS_PW_SERVICE .env || printf '\nDEXTER_REDIS_USERS="gateway identity ledger catalog portfolio planning reco billing commerce audit"\nREDIS_PW_SERVICE=dev_redis_svc_pw\n' >> .env
[ -f .env ] || cp .env.example .env
./scripts/verify.sh full          # static + infra (incl. Redis ACL + lab db) + mvn verify (unit + ITs: PG17, Kafka, Redis) + services + api-smoke (P00+P01+P02 lab) + k6
./scripts/trail-check.sh          # p00/p01/p02 pages, all services, the P01 lab and the P02 lab through the trail proxy
./scripts/run.sh stop
```
Open the trail: `./scripts/run.sh core && ./scripts/run.sh services && ./scripts/run.sh trail`, then port 8099 → `p02-libs.html`, toggle **Live**,
press **Lab status / start**, then **Get a valid token**. (After a manual `run.sh core` on a pre-r11 volume: `./scripts/run.sh nuke` first.)

## 9. Demo accounts (dev only)

- Created in P03. Passwords are already in `.env.example` (`DEMO_PASSWORD_*`).

## 10. Changelog (one line per response)

- 2026-10-16 P02b r11.1: repo `annsolomon/from` (code at root); CI `verify-full.yml` runs `verify.sh full` + `trail-check.sh` on a GitHub runner and force-pushes reports to branch `verify-reports`; gitleaks allowlists the P01 test cursor key. First real run: static/infra/ACL/unit/trail-check PASS; 2 test-code bugs fixed (`DemoCacheContractIT` id regex vs `"id": "` spacing, `RedisAclIT` case of Redis 7.4's "No permissions").
- 2026-10-04 P02b r11: P02 trail — `libs/dexter-lab` (dev-only `/_dev/p02/*` in ledger: JWT playground, idempotency, outbox/Kafka/projection/DLQ, cache controls) + `LabIssuerTest`/`P02LabAutoConfigurationTest`, `test-ui/p02-libs.html`, api-smoke P02 block (`p02_smoke.py`), trail-check P02, lab database/Redis user, A-23. P02 complete in code; WRITTEN.
- 2026-10-04 P02b r10: `dexter-cache` (ADR-0004) + Redis ACLs (template, compose `--aclfile`, verify checks), `ArchRules` (archunit 1.4.2), `TcpProxy`, `CacheContract`, demo on cache (detail + list, Path A/B), ITs `TenantCacheIT`/`RedisAclIT`/`DemoCacheContractIT`/`DemoCacheInvalidationIT`, `ArchRulesTest`; A-20..A-22, ADR-0004 §12, runbook. Type-checked (javac 25) + scripts/ACL run on Redis 7.0. WRITTEN.
- 2026-10-04 P02b (no zip, tool limit): first draft of the r10 work; nothing shipped.

- 2026-09-21 planning: MASTER_PLAN.md + this README created.
- 2026-09-21 P00 r1: repo bootstrapped (reactor, 8 lib + 10 service skeletons, compose core/obs/trail, role render, verify/run/smoke scripts, devcontainer, CI, trail kit skeleton, ADR-0001/0002). Static PASS in sandbox; role SQL proven on real PG16.
- 2026-09-21 P00 r2: Codespaces run → maven/services/smoke green. Fixed mailpit tag, gated + retrying infra checks, subshell fail-flag bug, k6 installer + hard-fail, loopback ports. P00 = UNIT-GREEN.
- 2026-09-21 P00 r3: 2nd Codespaces run (still r1 code) shows the pull failure is daemon-wide (`postgres:17`). images-pull now records per-image pull output + docker info. No other code change.
- 2026-09-21 P00 r4: fixed installer (curl 23 + API rate limit), sequential per-image pulls with diagnostics, report embeds failed-step logs.
- 2026-09-21 P00 r5: run 4 pinned the root cause (MinIO image deleted upstream); replaced with SeaweedFS 4.22 (ADR-0003, A-07); k6 green.
- 2026-09-21 P00 r6: run 5: images 5/5 and every container started, but Docker daemon state was corrupted (nil RWLayer, mass exit 255). verify full now resets compose state before each run.
- 2026-09-21 P00 r7: Codespaces run 6 → `verify.sh full` PASS 17/17. P00 = IT-GREEN (DONE after the trail check). Next: P01.
- 2026-09-21 P00 r8: trail healthcheck false-negative (localhost→::1 vs IPv4-only listen) fixed; obs healthchecks pinned to 127.0.0.1.
- 2026-09-21 P00 r9: trail healthy (200). Added ADR-0004 Redis cache invalidation + `dexter-cache` skeleton (A-08, built in P02).
- 2026-09-21 P00 r10: run 7 PASS 17/17 on r9 (with dexter-cache). P00 = IT-GREEN; DONE after the trail Live-proxy check in the P01 chat.
- 2026-09-21 P00 r11: trail proxy 400s on Tomcat services (Host `svc_*`) fixed + proven on real nginx; new scripts/trail-check.sh.
- 2026-09-21 P00 r12: trail kept the old nginx config (single-file bind mount + no recreate); now a directory mount + force-recreate.
- 2026-09-21 P00 r13: trail-check PASS 12/12 → **P00 DONE**. Next: P01.
- 2026-09-22 P01 r1: verified P00 DONE; confirmed every API P01 uses against Boot 4.0.8 / Spring 7.0.9 / Jackson 3.1.5 / Testcontainers 2.0.5 sources. No files shipped.
- 2026-09-22 P01 r2: reviewed the P01 libraries, type-checked core/db/web (main + tests) with JDK 25 against real sources, core + cursor harnesses PASS, SQL templates + RLS + keyset index plan proven on PG16; fixed cursor malleability and WHERE detection; wired dexter-web into 9 services; api-smoke/trail-check P01 blocks; p01-core.html. No files shipped.
- 2026-09-27 P02a r1: verified P01 (report 2026-09-27 PASS 17/17); split P02 (A-13); wrote BatchPurge, recovery_point column, Retry-After, dexter-idempotency. No files shipped (tool limit).
- 2026-09-27 P02a r2: wrote dexter-events, dexter-contracts, dexter-test kit, demo module + ITs; javac 25 type-check against dependency sources: 0 errors in P02 files. No files shipped (tool limit).
- 2026-10-04 P02b r9: run 17/17 PASS → P02b security + demo-on-JWT IT-GREEN; ADR-0007 proven; `.gitignore` ignores `*.zip`. Docs only.
- 2026-10-04 P02b r8: python3 preflight in `verify.sh`, Python devcontainer feature, skeleton check no longer passes on empty module list; README paths to `/workspaces/d2b`. Run on r7: maven PASS, 4 python-related FAILs.
- 2026-09-27 P02b r7: run on 2026-09-27 16/17 (k6 missing; unzip of r6 failed, so tested code unconfirmed). `verify.sh full` now auto-installs k6. README: unzip instructions with a sanity check, repo path `/workspaces/p02`. No Java changes.
- 2026-09-27 P02b r6: `dexter-security` (verifier, JWKS cache, interceptor, annotations, service-token client, auto-config), `TestJwt`, 34 security tests, demo on JWT + `DemoAuthIT`, nimbus 10.9.1 pin, ADR-0007, A-17..A-19, errors.md. Type-checked with javac 25 against dependency sources. P02b = WRITTEN (partial).
- 2026-09-27 P02b r1 (no files shipped): designed and wrote the first draft of dexter-security; type-check setup failed (relative sourcepath).
- 2026-09-27 P02a r5: r4 run `verify.sh full` PASS 17/17 in Codespaces → **P02a IT-GREEN**; ADR-0006 proven; README handed off to P02b. No code changes.
- 2026-09-27 P02a r4: r3 run failed at dexter-events unit tests; fixed `EnvelopeCodecTest.roundTrip` (IntNode vs LongNode). Sandbox now runs real Jackson 3.1.5 (compiled from source): codec round-trip, poison cases, CanonicalJson and MiniSchema checks all pass.
- 2026-09-27 P02a r3: static PASS; all new SQL run on PG16; ADR-0006, A-14..A-16, errors.md (Retry-After), README. **First P02a zip shipped.** P02a = WRITTEN.
- 2026-09-22 P01 r5: `verify.sh full` PASS 17/17 in Codespaces — P01 **DONE / IT-GREEN**. README marked, P02 handoff written.
- 2026-09-22 P01 r4: fixed the two test-side failures from the r3 run (Postgres uuid ordering → new `Uuids` comparator + test; RLS violation asserted on the cause), lab paging uses the comparator, junk paths removed.
- 2026-09-22 P01 r3: removed duplicate JaCoCo block; errors catalogue, ADR-0005, lab `.http` entries, README. First P01 zip shipped. P01 = WRITTEN.
