# Dexter v2 — Build README (living handoff)

**Last updated:** 2026-10-22 · **Chat/phase:** P11 DONE · **Model:** Claude Opus 5.5
**P11 is DONE (2026-10-22).** `verify.sh full` PASS + `trail-check.sh` PASS on a GitHub runner (repo `annsolomon/from`,
commit `bac4db8`, run 39703341828; reports on branch `verify-reports`). Next chat: **P12** (Portfolio B: calc + parser). Upload `MASTER_PLAN.md`
+ this README; the code is in the repo (clone it).

## 1. Phase status

| Phase | Name | Status (WRITTEN/COMPILED/UNIT-GREEN/IT-GREEN/DONE) | Evidence (verify-report date) | Notes |
|---|---|---|---|---|
| P00 | Bootstrap, toolchain, infra | **DONE** | `verify.sh full` PASS 17/17 (run 7, 2026-09-21) + `trail-check.sh` PASS 12/12 (r12, 2026-09-21) | |
| P01 | Core libs A (core/db/web) | **DONE** | `verify.sh full` 17/17 PASS (2026-09-22; re-run 2026-09-27 git 0fcfed1: core 28 unit, db 20 unit + 30 IT, web 12 unit, 10 service smoke); trail-check 15/15 PASS | IT-GREEN 2026-09-22 |
| P02 | Core libs B (idempotency/events/security/test) | **DONE** 2026-10-16 — verify full 17/17 + trail-check PASS (CI run 38913491961) |
| P03 | Harness, seed, trail kit | **DONE** 2026-10-16 | `verify.sh full` 19/19 PASS + trail-check PASS (CI run 38922523673, git 4dc9033): seed 25 unit + `SeedLoadIT` 4, 11 ArchitectureTests, pager.spec.js 18/18, medium seed generate+load 16 s, determinism checksum `a67ed5cf…` (identical in the sandbox and on CI) | DoD all ticked (§3) |
| P04 | Identity A | **DONE** 2026-10-17 | `verify.sh full` 21/21 PASS + trail-check PASS (CI run 39003795722, git 6e6ed9f): identity unit 19 + IT 41 (incl. IdempotencyContract 6, PaginationContract 9, RLS proof 7), api-smoke P04 26/26, outbox → Kafka 5 published / 0 pending | DoD all ticked (§3) |
| P05 | Identity B (MFA/admin/RBAC) | **DONE** 2026-10-17 | `verify.sh full` PASS + trail-check PASS (CI run 39016105238, git 0b04cc0): identity unit 33 + IT 65 (MFA, lockout, admin, break-glass, RLS), api-smoke P05 28/28 | DoD all ticked (§3) |
| P06 | Gateway | **DONE** 2026-10-17 | `verify.sh full` PASS + trail-check PASS (CI run 39020903788, git cf48871): gateway unit 10 + IT 19 (route table over 88 documented paths, edge filters, rate limit, breaker/SSE, activity → Kafka, degraded Redis/Kafka), api-smoke P06 18/18 | DoD all ticked (§3) |
| P07 | Ledger A (reference impl) | **DONE** 2026-10-17 | `verify.sh full` PASS + trail-check PASS (CI run 39023459091, git 4101870): ledger unit 5 + IT 31 (CRUD, PlanAssert on 1.2M rows, pagination + idempotency contracts, 1 000-op daily-totals property, RLS proof), api-smoke P07 30/30, ledger outbox → Kafka 4 published / 0 pending | DoD all ticked (§3) |
| P08 | Ledger B | **DONE** 2026-10-17 | `verify.sh full` PASS + trail-check PASS (CI run 39042585515, git 960e916): resolver 500/500 seed, 492/500 distorted, 42/42 payees unmatched; autocomplete p95 11 ms (p50 7 ms); 10k-row import 2.2 s + idempotent re-import; api-smoke P08 24/24 + import topic |  DoD all ticked (§3) |
| P09 | Catalog A | **DONE** 2026-10-17 | `verify.sh full` PASS + trail-check PASS (CI run 39046289166, git 1135b29): catalog unit 6 + IT 15 (admin draft→publish→version, public keyset/filters/search/potential, RLS 4, projection from the compacted topic = stored snapshot, PlanAssert on 50k cards incl. search inside the owner function), api-smoke P09 15/15 | DoD all ticked (§3) |
| P10 | Catalog B (ingestion) | **DONE** 2026-10-18 | `verify.sh full` PASS + trail-check PASS (CI run 39109141399, git c68837d): catalog unit 21 + IT 27 (pipeline: unchanged → no item, fee change → one item, approve idempotent sequential + concurrent, supersede/stale/draft-pending; collector: SSRF incl. redirects to private addresses, sitemap politeness, resume from checkpoint, abandoned runs, scheduler; API + permissions; RLS for ingestion tables), objectstore unit 2, api-smoke P10 16/16 (real admin MFA, SeaweedFS snapshot, approve → v2 public) | DoD all ticked (§3) |
| P11 | Portfolio A | **DONE** 2026-10-22 | `verify.sh full` PASS + trail-check PASS (CI run 39703341828, git bac4db8): portfolio unit 14 (BillingCycle, EmiMath, MatchScore, rewards domain, platform migration, ArchRules) + IT 46 (cards, transactions + statements + closer, links, auto-match accuracy on the seed's 357 merchants: precision 1.000 / recall 1.000 over 400 expenses, projection version guard + Kafka end to end, EMI, rewards + report, RLS proof on 10 tables, PaginationContract 9, IdempotencyContract 2 × 6), api-smoke P11 29/29 (auto-match over real Kafka) | DoD all ticked (§3) |
| P12 | Portfolio B (calc + parser) | NOT STARTED | — | |
| P13 | Planning | NOT STARTED | — | |
| P14 | Reco A | NOT STARTED | — | |
| P15 | Reco B (optimizer/SSE) | NOT STARTED | — | |
| P16 | Billing | NOT STARTED | — | |
| P17 | Commerce | NOT STARTED | — | |
| P18 | Audit/notify/privacy | NOT STARTED | — | |
| P19 | Hardening & release | NOT STARTED | — | |

## 2. In progress (exact stopping point)

- **P11 (2026-10-22): complete.** Nothing in progress. Next action: P12 (verify P11 first, then Portfolio B).
  P12 starts from: `card_transactions.source` (statement rows land here and reuse `Matcher`), `statements` (upload
  attaches to a period), `libs/dexter-objectstore` for statement files, `EmiMath`/`BillingCycle` (domain, pure) for
  the calc engine library, and the `ai-parser` skeleton.

### History (P11)

- r1 (`e335e27`): main code compiled; one test-compile error (Kafka record value must be bytes). r2 (`3e7277f`):
  a pre-existing gateway flake (`ResilienceIT`'s SSE stub outlived its test and lacked `X-Backend`) failed
  `RouteTableIT` before portfolio ran; r3 (`95bae52`) fixed the stub. 45 of 46 portfolio ITs green; the
  PaginationContract rejected a cursor of another card (`CURSOR_FILTER_MISMATCH`): path ids were bound into the
  cursor. r4 (`ed081e7`): path ids scope rows only; maven green, api-smoke 28/29 (the smoke expected a removed card
  to read back; the API answers 404). r5 (`bac4db8`): smoke fixed. All green (run 39703341828).

### History (P10)

- The first "go with 11" run (2026-10-17) wrote the schema, the collector, the review/promoter code and the
  dexter-objectstore move, then hit the usage limit before tests; the 2026-10-18 chat resumed from that tree.
- r1 (`65450b9`): main code compiled; one test-compile error (duplicate local name). r2 (`c83e07e`): 26 of 27 ITs
  green; `CatalogRlsIT` caught that the INGESTOR read policy also showed draft cards. r3 (`c68837d`): the policy
  shows published cards only. All green (run 39109141399).

### History (P09)

- r1 (`523a63e`): compiled; 2 IT failures: search seq-scanned 50k cards — under catalog_app's RLS policy the
  planner may not use the trigram index for LIKE/`%` (not leakproof), and the bulk insert left the GIN pending list
  full; the list test's custom card 3 had a base rule without the fuel exclusion. r2 (`1135b29`): search through
  the owner's SECURITY DEFINER `catalog.search_published_cards` (proved with auto_explain), VACUUM after the bulk
  load, test card fixed. All green (run 39046289166).

### History (P08)

- r1 (`3899b7e`, docs `dd8ec91`): compiled; 2 IT failures: `MerchantApiIT` loaded the seed merchants in a static
  `@BeforeAll` before Flyway had run (first class in the JVM); search's typo check needed word similarity 0.5 (the
  pg_trgm default 0.6 rejects one wrong letter in a 6-letter word). r2 (`960e916`): merchants loaded per test after
  the context; search sets `pg_trgm.word_similarity_threshold = 0.5` per transaction. All green (run 39042585515).

### History (P07)

- r1 (`9b71d4c`): everything green except ledger ITs: daily-totals upsert with a negative delta violated the
  `total_paise >= 0` CHECK (Postgres checks the proposed INSERT row before ON CONFLICT) → 500 on PATCH/DELETE; the
  date-range plan was bitmap + Sort. r2 (`4101870`): negative/in-place deltas via UPDATE; the date-range plan test
  allows a bounded sort (A-47). All green (run 39023459091).

### History (P06)

- r1 (`ffd0cd8`/`1672c72`): everything green except `ResilienceIT` breaker test: a connection reset from the
  service surfaced as Netty `NativeIoException` → 500. r2 (`ea0aeb7`): upstream `IOException` → 503; the open
  circuit surfaced as SCG `ServiceUnavailableException` (`@ResponseStatus`) → 500. r3 (`aa88308`): mapped; Maven
  green, p06 smoke crashed importing p04's helpers (Mailpit argv). r4 (`1b67d27`): fixed; one smoke check compared
  JWKS as text. r5 (`cf48871`): all green (run 39020903788).

- **P05 (2026-10-17): complete.** Nothing in progress. Next action: P06 (verify P05 first, then the gateway).
  P06 starts from: identity's Live trail calls (incl. `/api/v1/admin/*`) go through `Trail.liveRoutes`; per-IP
  throttling sees the socket address until the gateway forwards a trusted client IP; admin tokens carry `scope=admin`
  (the gateway can route `/api/v1/admin/*` and must keep `Authorization` intact).

### History (P05)

- r1 (`b293e8b`): compiled first time; context failed: keyset base SQL contained `ORDER BY` (inside `ARRAY(...)`).
- r2 (`c286445`): 60/65 ITs green; LockoutIT's own context had a different random KEK than the shared DB's signing
  keys (AES-GCM open failed), `identity_admin` lacked SELECT on `admin_mfa_totp`, health IT counted 2 migrations.
- r3 (`0b04cc0`): keys shared per JVM, column grants for `identity_admin` (no secrets/hashes), count 3.

- **P04 r2 (2026-10-17): complete.** Nothing in progress. Next action: P05 (verify P04 first, then Identity B).
  P05 starts from: `auth_find_login` returns `mfa_enabled=false` (replace it), `admin@demo.in` is still in
  `identity.seed_stage.admins` (P05's migration makes it real; `full.seed-small` then loads it), `security.alert` v1
  knows only `REFRESH_TOKEN_REUSE` (lockout kinds need v2 or a new type), the trail login for the admin persona calls
  `/api/v1/admin/auth/login` (add it to `Trail.liveRoutes`).

### History (P04)

- r1 (`1e92d0a`): identity service, tests. CI run 39003284076: everything compiled; 40/41 ITs green — only
  `IdentityHealthIT` expected 2 Flyway history rows (Flyway also records its schema-creation row, 3). Fixed in r2.
- r2 (`077d181` code, `6e6ed9f` docs): smoke/trail/docs, relay topics first. Run on `077d181` was cancelled by the
  docs push; run 39003795722 on `6e6ed9f` (same code) is the evidence.

- **P03 r1 (2026-10-16): complete.** Nothing in progress. Next action: P04 (verify P03 first, then Identity A).
  P04 owes the seed one step (ADR-0008): after `identity.accounts` exists, run `./scripts/seed.sh small` and fix
  `seed/…/Tables.ACCOUNTS` + `Generator` if the loader reports a missing column (`display_name` is not seeded;
  personas carry it in fixtures only).

### History (P02)

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

### P11 against its DoD (CI run 39703341828, git bac4db8, 2026-10-22)

| DoD / requirement | Result | Evidence |
|---|---|---|
| IT-GREEN | PASS | `full.maven`: portfolio unit 14 + IT 46 (`CardsIT` 2, `TransactionsIT` 3, `LinksIT` 3, `AutoMatchAccuracyIT` 1, `ProjectionIT` 2, `EmiIT` 2, `RewardsReportIT` 2, `PortfolioRlsIT` 9, `TransactionsPaginationContractIT` 9, `IdempotencyContractsIT` 12, `PortfolioHealthIT` 1) |
| RLS proof on all tables | PASS | `PortfolioRlsIT`: RlsProofKit (flags, isolation, fail-closed, WITH CHECK, no escape) on user_cards, statements, card_transactions, expense_links, ledger_expenses_proj, emi_plans, emi_schedule, reward_points_ledger (append-only: UPDATE/DELETE denied), card_savings_summary; catalog_products_proj readable by tenants, writable only by the PROJECTOR actor |
| Pagination and idempotency contracts | PASS | PaginationContract on `GET /v1/cards/{id}/transactions` incl. PlanAssert on 305,000 rows (`card_transactions_list`, no Sort); IdempotencyContract on `POST /v1/cards` and `POST /v1/cards/{id}/transactions` (replay, 422, concurrent, rollback on injected failure, 400 without key) |
| Auto-match accuracy on the seed | PASS | `AutoMatchAccuracyIT`: 400 seed-merchant expenses, 343 charged with bank-style descriptors + 69 decoys → 343 suggestions, 343 correct (precision 1.000, recall 1.000; gates 0.97 / 0.95); api-smoke: ledger expense → Kafka → suggestion → confirm |
| Projection version guard | PASS | `ProjectionIT`: stale and replayed catalog/expense versions are skipped, newer ones applied; Kafka end to end (compacted catalog topic + ledger topic → both projections → suggestion) |
| Trail page exercised | PASS | trail-check p11 (anon 401, asha's cards with current cycle, rewards balance, year report, suggestions page); Mock flow in jsdom (add/replay/409/rename/remove, record → reward + statement, ledger expense → suggestion → confirm, EMI ₹9,025.83 × 12, redeem 409, report), 0 errors |

### P10 against its DoD (CI run 39109141399, git c68837d, 2026-10-18)

| DoD / requirement | Result | Evidence |
|---|---|---|
| IT-GREEN | PASS | `full.maven`: catalog unit 21 (`CardExtractorTest` 5, `FetchPolicyTest` 4, `CardDiffTest` 4, `SchedulesTest` 2, P09's 6) + IT 27 (`IngestionPipelineIT` 3, `IngestionCollectorIT` 5, `IngestionApiIT` 3, `CatalogRlsIT` 5, P09's 11); `dexter-objectstore` `SigV4Test` 2 |
| Unchanged content → no review item | PASS | `IngestionPipelineIT.unchangedContentMakesNoReviewItem` (same facts: `noChange`; same bytes: `unchanged`, no snapshot); api-smoke re-run of the same page |
| Changed fee → one review item with the right diff | PASS | `aChangedFeeIsOneReviewItemAndApproveIsIdempotent` (`annualFeePaise` 50,000 → 99,900 against v1, nothing else); api-smoke on the seeded Saffron card |
| Promoter idempotent (double approve = one version) | PASS | same IT: sequential replay + two concurrent approves → one version, one promotion each; api-smoke approve twice → `replayed: true` |
| SSRF (localhost, 169.254.x, redirect to a private address) | PASS | `IngestionCollectorIT.theFetcherRefusesPrivateTargetsAndCapsEverything` + `blockedSourcesAreRefusedAtSaveOrRecordedPerFetch`; `FetchPolicyTest` (all special ranges incl. IPv6 forms); api-smoke 400s |
| Hash diffing, resumable checkpoints, per-host politeness | PASS | content-hash `unchanged`; `aCrashedRunResumesFromItsCheckpointAndDyingRunsAreAbandoned`; sitemap fetch gaps ≥ 140 ms at 150 ms politeness |
| Review queue admin-only, keyset | PASS | `IngestionApiIT` (401/403 by permission, keyset pages, cursor filter mismatch); `CatalogRlsIT.ingestionTablesAreForEditorsAndTheWorkerOnly` |
| Trail page exercised | PASS | trail-check p10 (admin endpoints 401 through `/api`, mock source fixture served); Mock flow in jsdom (run → item → approve v2 → replay → unchanged → supersede → reject), 0 errors |

### P09 against its DoD (CI run 39046289166, git 1135b29, 2026-10-17)

| DoD / requirement | Result | Evidence |
|---|---|---|
| IT-GREEN | PASS | `full.maven`: catalog unit 6 (`CardDocumentsTest` 4, `PlatformMigrationTest`, ArchRules) + IT 15 (`CatalogAdminIT` 4, `CatalogPublicIT` 2, `CatalogRlsIT` 4, `CatalogPlanIT` 3, `CatalogProjectionIT`, `CatalogHealthIT`) |
| Publish creates an immutable version, closes the previous one | PASS | `CatalogAdminIT` (v1 → edit draft → v2: v1 `effective_to` = v2 `effective_from`, rules of v1 closed, unchanged draft → `changed: false`); versions trigger refuses UPDATE/DELETE (`DX409`) even for the owner |
| No overlapping reward rules | PASS | exclusion constraint (card, category, merchant, `valid &&`) refuses an overlapping insert (`CatalogRlsIT`/`CatalogAdminIT`) |
| Event-driven projection equals the source | PASS | `CatalogProjectionIT`: consumer on `dexter.catalog.card.v1` rebuilds each card; equals the stored snapshot and the public detail |
| Public reads need no token, show published only | PASS | `CatalogPublicIT` (keyset by name, filters, draft hidden in list and search), api-smoke P09 (40 seeded cards, 3 pages, filters, ETag, search, potential, offers) |
| Admin is editor-only | PASS | `CatalogRlsIT` (catalog_admin without the editor role sees/writes nothing; catalog_app writes nothing), api-smoke (401 anonymous, 403 user token) |
| Search plan / list plans | PASS | `CatalogPlanIT` on 50,000 cards: list and next page walk `card_products_public` without Sort, network filter on `card_products_network`, search inner plan uses `card_products_name_trgm` (auto_explain), no seq scan |
| Trail page exercised | PASS | trail-check p09 (cards, detail, potential via `/api`); Mock flow in jsdom, 0 errors |

### P08 against its DoD (CI run 39042585515, git 960e916, 2026-10-17)

| DoD / requirement | Result | Evidence |
|---|---|---|
| IT-GREEN | PASS | `full.maven`: ledger unit + IT green (resolver, merchant API, search, bulk, import, insights, RLS for import tables, search PlanAssert) |
| Resolver accuracy report | PASS | `ResolverAccuracyIT` (below in §4): 500/500 seed descriptors, 492/500 distorted names, 42/42 local payees left unmatched |
| Autocomplete latency on the medium seed's merchant table | PASS | `MerchantApiIT.autocompleteP95IsWithinFiftyMilliseconds` (200 cold queries over HTTP: p50 7 ms, p95 11 ms, max 27 ms) |
| 10k-row import idempotent | PASS | `ImportIT` (10,000 rows in 2.2 s: 9,997 created + 3 row errors; same file again → 9,997 duplicates, 0 new rows, 0 new events); api-smoke import through SeaweedFS presigned PUT + re-import |
| Bulk partial failure semantics | PASS | `BulkExpensesIT` (created / duplicate / invalid per item, replay, 500 max); api-smoke bulk |
| Search: tenant + date btree then trigram recheck (PlanAssert) | PASS | `ExpensePlanIT.searchUsesTheTenantDateBtreeThenRechecksTheText` (expenses_list, no seq scan, 1.2M rows) |
| Events | PASS | `ledger.import.completed` on `dexter.ledger.import.v1` (api-smoke offsets); bulk/import expense events via one batched outbox insert |
| Trail page exercised | PASS | trail-check p08 (autocomplete, calendar, summary, search via `/api`); Mock flow in jsdom, 0 errors |

### P07 against its DoD (CI run 39023459091, git 4101870, 2026-10-17)

| DoD / requirement | Result | Evidence |
|---|---|---|
| IT-GREEN | PASS | `full.maven`: ledger unit 5 + IT 31, 0 failures |
| RLS (FORCE, proof kit) | PASS | `LedgerRlsIT`: RlsProofKit on `expenses`, `expense_daily_totals`, `categorization_rules`, `categories` (FORCE RLS, tenant isolation); static rls-gate; api-smoke cross-tenant GET/PATCH/DELETE 404 |
| PlanAssert on large data | PASS | `ExpensePlanIT` on 1.2M generated rows (A-46): list/next page/card/merchant without Sort, date range + category bounded, never a seq scan |
| Pagination contract | PASS | `ExpensesPaginationContractIT` (window, tamper, filter mismatch, limit bounds, no duplicates/gaps) |
| Idempotency contract | PASS | `CreateExpenseIdempotencyContractIT` (replay, 422 reuse, parallel, failure leaves no key); api-smoke replay/422/400 |
| ETag / If-Match | PASS | `ExpenseCrudIT` (428, 412, no-op keeps version); api-smoke |
| Daily totals never drift | PASS | `DailyTotalsPropertyIT` (1 000 random create/patch/delete/restore vs `GROUP BY`); api-smoke totals follow create/patch/delete/restore |
| Outbox → Kafka | PASS | api-smoke: 4 ledger expense events published, 0 pending; `dexter.ledger.expense.v1` offsets 4 |
| Trail page exercised | PASS | trail-check p07 (list with cursor, daily totals, categories through `/api`); Mock flow in jsdom, 0 errors |

### P06 against its DoD (CI run 39020903788, git cf48871, 2026-10-17)

| DoD / requirement | Result | Evidence |
|---|---|---|
| IT-GREEN | PASS | `full.maven`: gateway unit 10 + IT 19, 0 failures; identity (audience `gateway`, forwarded headers) still green |
| `docs/api/routes.md` complete | PASS | `RouteDocsTest` (doc patterns = `RouteTable` patterns; each example matches its documented pattern, service, access, class) |
| Route table test (WireMock backends) | PASS | `RouteTableIT` (every documented example → right service, path unchanged, token forwarded; token routes 401 without a token; unknown paths 404 with or without) |
| Rate-limit 429 + headers | PASS | `RateLimitIT` (auth 10/min per IP, calc 60/min per account, `RateLimit-Policy`/`RateLimit`/`Retry-After`, bucket refill with an injected clock); api-smoke 11th sign-in 429 |
| Spoofed `X-Account-Id` stripped | PASS | `EdgeFiltersIT.spoofedIdentityHeadersAreStripped` (backend never sees X-Account-*/X-Actor-*/X-User-*/X-Roles/X-Tenant-*) |
| Public route works without a token | PASS | `EdgeFiltersIT.publicRouteWorksWithoutAToken`, `RouteTableIT`; api-smoke demo sign-in through the gateway |
| SSE isn't cut at 10 s | PASS | `ResilienceIT.sseIsNotCutAfterTenSeconds` (12 s stream with 4 s silences while normal routes time out at 3 s → 504) |
| Breaker per route (not SSE) | PASS | `ResilienceIT.breakerOpensOnFailuresAndStopsCallingTheService`; `RouteDocsTest` only `reco-stream` skips it |
| Activity events, sampled + never blocking | PASS | `ActivityIT` (envelope + data on `dexter.gateway.activity.v1`), `DegradedIT` (Kafka and Redis unreachable: reads 200 fast, sign-in 503); api-smoke activity offsets > 0 |
| Trail page exercised | PASS | trail-check p06 (404 problem + request id + nosniff, `/api/v1/me` with RateLimit-Policy, bridge gone; P04/P05 checks now via `/api`); Mock flow in jsdom, 0 errors |

### P05 against its DoD (CI run 39016105238, git 0b04cc0, 2026-10-17)

| DoD / requirement | Result | Evidence |
|---|---|---|
| IT-GREEN | PASS | `full.maven`: identity unit 33 + IT 65, 0 failures; other modules unchanged and green |
| RFC 6238 vectors | PASS | `TotpTest` (all six SHA1 vectors, 8 and 6 digits, window, Base32 RFC 4648) |
| TOTP step replay rejected | PASS | `MfaFlowIT` (code used to confirm is refused at sign-in; same code on a new challenge refused); api-smoke "replayed TOTP step: 401" |
| Recovery codes single-use | PASS | `MfaFlowIT` (second use 401, regeneration kills old codes), `RecoveryCodesTest`; api-smoke |
| Lockout thresholds | PASS | `RedisLoginThrottleIT` (free → 2 s → 4 s → lock at the limit, cap, reset, per-IP) as the ACL-restricted Redis user; `LockoutIT` (lock refuses the right password, unknown emails alike, success resets, step-up throttled, `ACCOUNT_LOCKED` once); api-smoke 429 |
| Permission matrix | PASS | `AdminIT` (SUPPORT/FINANCE/CATALOG_EDITOR tokens → perms; 403 `missingPermissions`; user ↔ admin token separation); `AdminRlsIT` (actor-role reads, definer functions) |
| Break-glass expiry | PASS | `AdminIT.breakGlassExpires` (elevation moved past its hour → SUPER_ADMIN refused, refresh drops the role), `breakGlassManagesAdminsAndEndsAtOnce` (token exp ≤ elevation end, ended early → 403 at once); DB refuses ineligible elevations |
| Admin endpoints unreachable without MFA + permission | PASS | admin tokens only after TOTP (setup forced at first sign-in), `AdminGuard` (scope admin + amr mfa), `@RequiresPermission`, `identity_admin` policies; api-smoke + trail-check `p05-anon` 401 |
| Trail page exercised | PASS | trail-check p05 page + admin challenge + anon 401 + `/v1/me/mfa` + bridge; Mock flow in jsdom (enrol → MFA login → replay refused → recovery → admin setup → lookup → 403 → break-glass → invite → end → old token 403 → throttle 429, 0 errors) |

### P04 against its DoD (CI run 39003795722, git 6e6ed9f, 2026-10-17)

| DoD / requirement | Result | Evidence |
|---|---|---|
| IT-GREEN | PASS | `full.maven`: identity unit 19 + IT 41, all 0 failures; every other module unchanged and green |
| Reuse-detection test passes | PASS | `AuthFlowIT.reusingARotatedRefreshTokenRevokesTheWholeFamily` (family + session revoked, alert + revoked events); api-smoke "replay … 401 REFRESH_TOKEN_REUSED", "family revoked" |
| No endpoint leaks whether an email exists | PASS | `EnumerationSafetyIT` 4 (identical login/register/forgot answers, timing within tolerance); api-smoke identical-answer + forgot checks |
| Full auth flow | PASS | `AuthFlowIT` 7; api-smoke register → Mailpit email → verify → login → /me → refresh → logout |
| JWKS rotation, old-key tokens valid until expiry | PASS | `JwksRotationIT` 2 (verified over HTTP by dexter-security's verifier); api-smoke dev rotation |
| RLS proof on all tenant tables | PASS | `IdentityRlsIT` 7 (accounts, account_keys, verification_tokens, sessions, refresh_tokens, idempotency_keys + definer EXECUTE grants) |
| Sessions pagination contract | PASS | `SessionsPaginationContractIT` 9 incl. PlanAssert (index `sessions_active_list`, no Sort) |
| Idempotency contract (register, Idem REQ) | PASS | `RegistrationIdempotencyContractIT` 6 |
| Events in the same transaction, relayed | PASS | outbox rows asserted per flow in ITs; api-smoke "identity outbox -> Kafka: 5 published, 0 pending", topics exist |
| Trail page exercised | PASS | trail-check p04 page + JWKS + demo login + /me + bridge through the proxy; Mock flow run in jsdom (register → replay → verify → login → refresh → reuse → patch/412/428 → sessions → rotate → logout-all, 0 errors) |
| Seed owed by P03 | PASS | `full.seed-small` loads into the real `identity.accounts`; api-smoke "asha@demo.in signs in" |

### P03 re-check in the P04 chat (CI run 38924314244, git 85de7f1, 2026-10-16)

The latest report on `verify-reports` before P04 work started is for the P03 docs commit: `verify.sh full` PASS (19/19
incl. `full.seed-medium` 17 s, `full.seed-determinism` checksum `a67ed5cf…`) and trail-check PASS. Every P03 DoD
item still holds; the uploaded BUILD_README equals the repo copy. P03 owed P04 one step (ADR-0008): load the small
seed into the real `identity.accounts` once its migration exists — done as `full.seed-small` (after services-up) and
proven by the api-smoke demo login. The seed's column list for `accounts` matches the P04 table (extra P04 columns
have defaults), so `seed/…/Tables` needed no change. No P03 code issue found.

### P03 against its DoD (CI run 38922523673, git 4dc9033, 2026-10-16)

| DoD / requirement | Result | Evidence |
|---|---|---|
| `seed.sh medium` works | PASS | `full.seed-medium`: generate 7 s + load (13 tables, 2,340,273 rows as `<svc>_owner`), total 16 s, limit 600 s |
| Medium seed < 10 min | PASS | 16 s on CI; 35 s in the sandbox |
| Same seed → same checksum | PASS | `full.seed-determinism` (2 medium generations) + `GeneratorTest.sameSeedGivesTheSameBytesAndChecksum`; CI and sandbox medium checksums identical (`a67ed5cf…`) |
| `pager.spec.js` green | PASS | `static.trail-pager` 18/18 (node 22); mutation check: breaking dedupe, filter binding, the keyset predicate or the trigger offset each fails ≥ 1 test |
| Trail index opens with mock data | PASS | trail-check: `p03-harness.html`, all 7 seed fixtures served byte-identical, `trail.js` has the kit; jsdom render: 25 shown / 50 buffered, replay, 400 codes, outbox event, 0 errors (p01/p02 pages too) |
| ArchitectureTest in every servlet service (A-22) + gateway | PASS | 9 × 1 + `GatewayArchitectureTest` 2, all green |
| Argon2 hashes usable by P04 | PASS | `PasswordsTest` (Spring `Argon2PasswordEncoder`) |
| Loader: staging / real FORCE-RLS table / refuse overwrite / missing column | PASS | `SeedLoadIT` 4/4 (PG17); sandbox PG16: 1.2 M rows into a FORCE-RLS `ledger.expenses` with the planned CHECKs, FORCE restored, `ledger_app` sees 500 for Asha / 0 without context |
| Daily totals = SUM | PASS | `GeneratorTest.dailyTotalsEqualTheSumOfLiveExpenses`; SQL on the medium seed: 0 mismatching keys |

### P02 re-check in the P03 chat

P02's DoD still holds: the same run re-ran every P02 IT, api-smoke P02 block (36 checks) and the trail-check P02 lab — all PASS.
No P02 code issue found while wiring ArchRules into the services (the gateway "needs care" gap is closed by excluding
dexter-test's transitive deps, A-26).

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

**P11 Portfolio A (DONE)** — ADR-0016, errors.md portfolio section

| Piece | What it does |
|---|---|
| Schema `V202610221000__platform` + `V202610221001__portfolio_core` | `catalog_products_proj` (global; PROJECTOR writes), `user_cards` (active/closed/removed, live unique per product + last4), `statements` (per card period, debits/credits/total/min due, status), `card_transactions` (kind, direction, statement, expense link, client ref), `ledger_expenses_proj`, `expense_links` (suggested/confirmed/rejected, one confirmed per side), `emi_plans` + `emi_schedule`, `reward_points_ledger` (append-only for the app), `card_savings_summary` (month). FORCE RLS everywhere; `portfolio.close_ended_statements` SECURITY DEFINER; extended statistics on (account_id, user_card_id) |
| `domain` | `BillingCycle` (IST, day 31 = last), `EmiMath` (exact reducing balance + GST), `MatchScore` (normalise, word-start containment, truncated descriptors, trigram ≥ 0.45; 0.75 merchant + 0.25 days), `RewardTerms` (rule choice, earning), `PointsBalance` (FIFO expiry), `PortfolioErrors` |
| `app` | `CardService`, `TransactionService` (statement find-or-create, rewards, savings, matcher), `LinkService`, `EmiService`, `RewardService`, `ReportService`, `Matcher` (both directions), `ProjectionService` (catalog + expense consumers under the version guard), ports |
| `infra` | `CardRepository` (+ projection store), `TransactionRepository` (keyset lists, closer via the owner function), `LinkRepository`, `EmiRepository`, `RewardRepository` (filter mask) |
| `api` | `CardController` (`/v1/cards` CRUD, statements, transactions), `LinkController` (`/v1/links/suggestions`, `:confirm`, `:reject`), `EmiController` (`/v1/emi-plans` CRUD + schedule), `RewardController` (`/v1/rewards/balance|ledger`, `/v1/portfolio/report`) |
| `config` | `PortfolioConfiguration`, `StatementCloserJob`, `PortfolioRelayConfiguration` (outbox relay; ensures consumed topics too), `PortfolioConsumersConfiguration` (`catalog-projection`, `expense-match` with retry/DLQ) |
| Contracts | `portfolio.card.added|updated|removed` v1 (`CardChangedV1`), `portfolio.transaction.recorded` v1, `portfolio.link.confirmed` v1; `ExpenseStateV1.merchant` (A-62) |
| Seed | `CATALOG_PRODUCTS_PROJ` rows from the seeded catalog (projected_version 0); `user_cards` load into the real table |
| Tests | see §3; support `PortfolioIT` (three catalog products), `Events`, `SeedMerchants` |
| Trail / smoke | `test-ui/p11-portfolio.html` (trail.js: user-card mock in the API shape, `events.on`); `scripts/lib/p11_smoke.py`; trail-check P11 |

**Query→Index (P11):**

| Query | Index | Plan |
|---|---|---|
| cards list (newest first) | `user_cards_list` (account_id, created_at, id) partial live | Index |
| one live card per product + last4 | `user_cards_key` unique partial | Index |
| statements of a card | `statements_list` (account_id, user_card_id, period_start, id) | Index |
| statement of a posting day / closer | `statements_card_period_key` / `statements_open` (period_end) partial | Index |
| card transactions (newest first) | `card_transactions_list` (account_id, user_card_id, posted_at, id) + statistics `card_transactions_card` | Index (PlanAssert, 305k rows) |
| client ref dedupe | `card_transactions_client_ref` unique partial | Index |
| match candidates | `card_transactions_match` partial unlinked debits; `ledger_expenses_proj_match` partial live | Index |
| suggestions | `expense_links_suggested` partial | Index |
| EMI plans / schedule | `emi_plans_list` / `emi_schedule_pkey` | Index |
| rewards ledger / balance / earn once | `reward_points_ledger_list` / `_card` / `_source` unique partial | Index |
| report | `card_savings_summary_pkey` | Index |

**P10 Catalog B (DONE)** — ADR-0015, errors.md catalog ingestion section

| Piece | What it does |
|---|---|
| Schema `V202610171600__catalog_ingestion` | `sources` (issuer, optional card, URL + host, kind html/pdf/sitemap/image, cron, soft delete, version), `crawl_runs` (source ids, checkpoint jsonb, attempts, claim), `snapshots` (SHA-256, content type, size, object key), `extractions` (jsonb, extractor version, confidence), `review_items` (diff jsonb against `base_version`, one pending per card, decision shape CHECK), `promotions` (unique per item). FORCE RLS: editors everything, actor role INGESTOR (worker) writes runs/snapshots/extractions/items and reads published cards only; catalog_app nothing. `GRANT DELETE` on drafts (discard) |
| `domain` | `FetchPolicy` (URL + address rules), `CardExtractor` (pattern-v1), `HtmlText`, `Sitemaps` (XXE-free), `CardDiff` (compute/apply), new `CatalogErrors` |
| `app` | `Promoter` (the only writer of published data; P09 publish moved here), `IngestionAdminService` (sources, runs), `IngestionRunner` (scheduler, claim, per-source checkpoint, politeness, snapshot → extraction → diff → item), `ReviewService` (queue, approve with replay, reject), ports `IngestionStore`, `PageFetcher`, `SnapshotStore`, `Schedules` |
| `infra` | `IngestionRepository`, `SafeFetcher` (DNS check per hop, manual redirects, caps), `S3SnapshotStore` |
| `api` | `IngestionController` (`/v1/admin/ingestion/sources` CRUD + `/{id}/runs`, `/runs`), `ReviewController` (`/v1/admin/review`, `/{id}`, `:approve`, `:reject`), `DELETE /v1/admin/catalog/cards/{id}/draft` |
| `config` | `IngestionWorker` (poll 2 s, schedule 60 s), `dexter.catalog.ingestion.*` (allowlist, dev hosts, caps, politeness, stale 10 min, 3 attempts, snapshot bucket) |
| Library | `libs/dexter-objectstore`: `SigV4` (moved from ledger) + `S3Objects` (put/open/presign, bucket on first use); ledger's `S3ImportFiles` uses it |
| Tests | see §3; support `IngestionIT`, `MockIssuerSite` (JDK HttpServer, hit log), `MemorySnapshotStore`, test DNS for `*.issuer.test` |
| Trail / smoke | `test-ui/p10-ingestion.html` + `fixtures/p10-source.json`; `scripts/lib/p10_smoke.py` (mock issuer site on :18091, admin TOTP from `.run/admin-totp` saved by the P05 smoke); trail-check P10 |

**Query→Index (P10):**

| Query | Index | Plan |
|---|---|---|
| sources list (newest first) | `sources_list` (created_at, id) partial live | Index |
| one live source per URL | `sources_url_key` unique partial | Index |
| scheduler: due sources | `sources_due` (next_run_at) partial | Index, SKIP LOCKED |
| worker claim | `crawl_runs_claim` (created_at) partial queued/running | Index, SKIP LOCKED |
| runs list | `crawl_runs_list` (created_at, id) | Index |
| latest snapshot of (source, url) | `snapshots_latest` (source_id, url, fetched_at DESC) | Index, LIMIT 1 |
| review queue by status (oldest first) | `review_items_queue` (status, created_at, id) | Index |
| pending item of a card | `review_items_card_pending` unique partial | Index |
| promotion of an item | `promotions_review_item_id_key` | Index |

**P09 Catalog A (DONE)** — ADR-0014, errors.md catalog section

| Piece | What it does |
|---|---|
| Schema `V202610171500__platform` + `V202610171501__catalog_core` | `issuers`; `card_products` (published face + status draft/published/retired, `current_version`, `content_hash`); `card_product_drafts` (editor only); `card_product_versions` (snapshot jsonb, validity, immutable by trigger `DX409`, one open version); `reward_rules` (per version, ltree category, merchant, exclusions, caps, EXCLUDE gist on overlap); `milestones`; `offers` (active/withdrawn). FORCE RLS: catalog_app reads published only, catalog_admin needs actor role CATALOG_EDITOR/SUPER_ADMIN (`catalog.is_editor()`); `catalog.search_published_cards` SECURITY DEFINER |
| `domain` | `CardDocument` (+ Rule, Milestone), `CardDocuments` (validation, normalisation, canonical form), `CardSnapshots` (seed-shaped snapshot), `CatalogErrors` |
| `app` | `CatalogQueryService` (sentinel PUBLIC tenant; list/detail/search/potential/offers), `CatalogAdminService` (issuers, cards, draft If-Match, publish no-op by hash, retire, offers), ports `CatalogStore`, `AdminCatalogStore`, `CardFilter`, `CatalogEvents` |
| `infra` | `CatalogRepository` (static keyset query per filter mask on (name, id), category-rate filter with NOT EXISTS, potential via DISTINCT ON), `AdminCatalogRepository` |
| `api` | `CatalogController` (`@PublicEndpoint`: `/v1/catalog/cards`, `/cards/search`, `/cards/{slug}` + ETag, `/offers`, `/categories/potential`), `AdminCatalogController` (`/v1/admin/catalog/**`, `catalog:read/write/publish`: issuers, cards, draft GET/PUT, versions, `:publish`, `:retire`, offers, `:withdraw`) |
| `config` | `CatalogConfiguration` (second pool as catalog_admin), `CatalogRelayConfiguration` (outbox relay; compacted `dexter.catalog.card.v1`, `dexter.catalog.offer.v1`) |
| Contracts | `catalog.card.published` v1, `catalog.card.retired` v1 (`CardPublishedV1`), `catalog.offer.changed` v1 (`OfferChangedV1`) |
| Tests | unit `CardDocumentsTest` 4, `PlatformMigrationTest`, ArchRules; IT `CatalogAdminIT` 4, `CatalogPublicIT` 2, `CatalogRlsIT` 4, `CatalogPlanIT` 3, `CatalogProjectionIT` 1, `CatalogHealthIT` 1 |
| Trail / smoke | `test-ui/p09-catalog.html` (trail.js: ascending collections, query-aware filters); `scripts/lib/p09_smoke.py` (api-smoke P09); trail-check P09 |

**Query→Index (P09):**

| Query | Index | Plan |
|---|---|---|
| public list (name, id) | `card_products_public` (partial published) | Index scan in order, no Sort (PlanAssert) |
| list by network / issuer | `card_products_network` / `card_products_issuer` | Index scan in order |
| category ≥ X bps | `reward_rules` by (card, version) + EXISTS/NOT EXISTS | Nested loop per card |
| detail by slug | `card_products_slug_key` + `card_product_versions_current` | Index |
| search | `card_products_name_trgm` (GIN, partial) inside `search_published_cards` | Bitmap (auto_explain) |
| categories/potential | reward_rules of current versions, DISTINCT ON card | Bounded (published cards) |
| admin list | `card_products_admin` (name, id) | Index |
| active offers | offers by status/validity | Small table |

**P08 Ledger B (DONE)** — ADR-0013, errors.md ledger P08 section

| Piece | What it does |
|---|---|
| Schema `V202610171300__ledger_merchants_imports` | `merchants` (global, app reads active rows only, no write grant), `merchant_aliases` (+ generated `alias_compact`; GIN trigram on normalized and compact, text_pattern_ops prefix), `import_jobs` / `import_errors` (RLS), `import_claim_next(stale, max_attempts)` SECURITY DEFINER (SKIP LOCKED), `GRANT TEMPORARY` to `ledger_app` (COPY staging) |
| `domain` | `MerchantText` (descriptor normalisation, variants, compact forms, truncation), `EditDistance` (OSA), `ImportRow` (CSV row parsing, derived client refs), `DailyDelta.sum`, new `LedgerErrors` (`IMPORT_NOT_STARTABLE`, `RULE_LIMIT_REACHED`) |
| `app` | `MerchantResolver` (prefetch through Redis outside transactions, sessions with rules), `MerchantService` (autocomplete, resolve), `ExpenseService.search` + resolver on create/PATCH, `BulkExpenseService`, `ImportService` + `ImportProcessor`, `InsightService` (calendar, summary), `RuleService`; ports `MerchantStore`, `RuleStore`, `ImportStore`, `ImportFiles`, `ReferenceCache`, `LiabilitySource` (NONE until P11/P13) |
| `infra` | `MerchantRepository`, `RuleRepository`, `ImportRepository` (COPY via pgjdbc CopyManager into a temp table), `RedisReferenceCache` (dexter-cache global scopes `merchant-alias` 1 h, `merchant-ac` 10 min), `ExpenseRepository` search / multi-row insert / groups, `objectstore/SigV4` + `S3ImportFiles` |
| `api` | `MerchantController`, `BulkController` (`/v1/expenses:bulk`), `ImportController` (`/v1/imports`, `/{id}:start`, `/{id}`), `InsightController` (`/v1/calendar`, `/v1/summary`), `RuleController`, `GET /v1/expenses/search` |
| `config` | `ImportWorker` (poll 1 s, drain), Redis on ACL user `ledger` (health indicator off), `dexter.ledger.imports.*` (endpoint, public endpoint, bucket, keys, 10 MB, 50k rows, stale 10 min, 3 attempts) |
| Libraries / contracts | `OutboxWriter.appendAll` (multi-row outbox insert, 500 per statement); `ledger.import.completed` v1 (`ImportCompletedV1`) |
| Tests | unit `MerchantTextTest` 5, `SigV4Test` 2 (AWS documentation vector); IT `ResolverAccuracyIT` 2, `MerchantApiIT` 5, `ExpenseSearchIT` 2, `BulkExpensesIT` 2, `ImportIT` 2, `InsightIT` 2, `LedgerRlsIT` +3, `ExpensePlanIT` +1 |
| Trail / smoke | `test-ui/p08-ledger-plus.html`; `scripts/lib/p08_smoke.py` (api-smoke P08 + `dexter.ledger.import.v1` offsets); trail-check P08 |

**Resolver accuracy (ResolverAccuracyIT, CI run 39042585515):**

| Set | Top-1 | Rate |
|---|---|---|
| seed descriptors (500: UPI, card ×3 shapes, net banking, wallet, cash) | 500 / 500 | 100.0 % |
| distorted names (500: dropped letter, swap, "store/online/app", 12-char cut) | 492 / 500 | 98.4 % |
| local payees → no merchant (42) | 42 / 42 | 100.0 % |

Distorted set by stage: exact 129/131, compact 138/138, trigram 175/177, typo (OSA) 50/52, none 0/2. Misses are
genuinely ambiguous ("APPLESTORE" vs Apple Store India, "IKAONLINE" vs Ikea Online, "CROMAONLINE" exact alias of
Croma Online vs label Croma).

**Query→Index (P08):**

| Query | Index | Plan |
|---|---|---|
| search (q + 12-month window) | `expenses_list` (tenant + date), text recheck (ILIKE, word similarity) | Index/bitmap on expenses_list, never a seq scan (sort allowed, bounded by the range) |
| resolver exact / compact alias | `merchant_aliases_key` / `merchant_aliases_compact` | Index |
| resolver truncated prefix | `merchant_aliases_compact` (text_pattern_ops) | Index |
| resolver trigram / typo candidates | `merchant_aliases_compact_trgm` (GIN, `%`) | Bitmap |
| autocomplete | `merchant_aliases_trgm` (GIN) for `%`/LIKE | Bitmap (635 aliases) |
| bulk/import client_ref dedupe | `expenses_client_ref` (ON CONFLICT … DO NOTHING) | Index |
| import job by id / claim | pkey / `import_jobs_claim` (partial on queued/running) | Index |
| calendar, summary by day/category | `expense_daily_totals_pkey` | Index |
| summary by merchant / payment method | `expenses_list` range | Index |

**P07 Ledger A (DONE)** — ADR-0012, errors.md ledger section

| Piece | What it does |
|---|---|
| Schema | `V202610171200__platform` (rendered templates, `PlatformMigrationTest` drift guard) + `V202610171201__ledger_core`: `categories` (ltree path 1–4 levels, 48 system rows `md5('ledger.category:'‖path)::uuid`, custom rows per account, `UNIQUE NULLS NOT DISTINCT (account_id, path)`), `expenses` (paise `bigint`, `version`, soft delete `deleted_at`, `client_ref`, card only for card payments), `expense_daily_totals` (PK account × IST day × category root, counts ≥ 0), `categorization_rules` (P08). FORCE RLS + owner policies everywhere; `ledger_app` has no DELETE on expenses |
| `domain` | `CategoryPaths` (validate, root, slug, `general` default), `DailyDelta` (before/after → bucket changes), `LedgerTime` (Asia/Kolkata day), `PaymentMethod`, `LedgerErrors` |
| `app` | `ExpenseService` (create Mode A idempotent → 201 + Location + ETag; get; keyset list with 8 filters; daily totals ≤ 366 days; merge PATCH with If-Match + `FOR UPDATE`; soft delete; restore; every write = daily delta + outbox event), `CategoryService` (list visible; create under a system root, optional key); ports `ExpenseStore`, `DailyTotalsStore`, `CategoryStore`; `LedgerEvents` |
| `infra` | `ExpenseRepository` (static keyset query per filter mask, cached; `expenses_client_ref` duplicate → `CLIENT_REF_TAKEN`), `DailyTotalsRepository` (upsert, delete at 0 txns), `CategoryRepository` |
| `api` | `ExpenseController` (`/v1/expenses`, `/daily-totals`, `/{id}`, `/{id}:restore`), `CategoryController`; `Callers.user` refuses admin tokens |
| `config` | `LedgerConfiguration`, `LedgerRelayConfiguration` (outbox relay → `dexter.ledger.expense.v1`), `LedgerProperties`; JWT audience `ledger`, cursor key `LEDGER_CURSOR_KEY_C1` |
| contracts | `ledger.expense.{created,updated,deleted,restored}` v1 (`ExpenseChangedV1` with `ExpenseStateV1` before/after, nullable) + JSON schemas |
| Tests | unit `LedgerDomainTest` 3, `PlatformMigrationTest` 1, `LedgerArchitectureTest` 1; IT `ExpenseCrudIT` 5, `ExpensePlanIT` 5 (PlanAssert on 1.2M generated rows), `ExpensesPaginationContractIT` + `CreateExpenseIdempotencyContractIT` (dexter-test contracts), `DailyTotalsPropertyIT` (1 000 random ops vs `GROUP BY`), `LedgerRlsIT` 4 (RlsProofKit), `LedgerHealthIT` |
| Trail / smoke | `test-ui/p07-ledger.html` (window + debug, CRUD with ETag, idempotency tester, daily totals live, isolation, categories); `scripts/lib/p07_smoke.py` (api-smoke P07 block) + ledger outbox → Kafka check; trail-check P07 |

**Query→Index (P07):**

| Query | Index | Plan (1.2M rows, 30k for one account) |
|---|---|---|
| list, newest first (+ next page via cursor) | `expenses_list (account_id, occurred_at DESC, id DESC) WHERE deleted_at IS NULL` | Index Scan, no Sort |
| list `cardId=` | `expenses_card (account_id, user_card_id, occurred_at DESC, id DESC)` | Index Scan, no Sort |
| list `merchantId=` | `expenses_merchant (account_id, merchant_id, occurred_at DESC, id DESC)` | Index Scan, no Sort |
| list `category=` (subtree) | `expenses_cat GIST (account_id, category_path)` | Bitmap scan + Sort (bounded by the subtree) |
| list `from`/`to` | `expenses_list` (range on `occurred_at`) | Bitmap Index Scan + Sort of the account's rows in the range (planner uses the average rows per account) |
| list `minPaise`/`maxPaise`/`paymentMethod` | `expenses_list` (filters on the ordered scan) | Index Scan, no Sort |
| get/patch/delete by id | `expenses_pkey` | Index Scan |
| import de-dup `clientRef` | `expenses_client_ref UNIQUE (account_id, client_ref)` | Index |
| daily totals range | `expense_daily_totals_pkey (account_id, day, category_root)` | Index Scan |
| categories visible | `categories (account_id, path)` unique + GiST `path` | Seq/Index (≤ a few hundred rows) |

**P06 Gateway (DONE)** — ADR-0011, `docs/api/routes.md`

| Piece | What it does |
|---|---|
| `domain` | `RouteTable` (11 routes, 88 patterns+examples documented; SSE `reco-stream`; public allowlist; dev route `/_dev/p04/**`), `RouteClass` (auth/write/read/calc), `GatewayErrors` (`UPSTREAM_UNAVAILABLE`, `UPSTREAM_TIMEOUT`) |
| `infra` | WebFilters in `FilterOrder`: `ActivityFilter` → `RequestIdFilter` → `SecurityHeadersFilter` → `StripIdentityHeadersFilter` (+ `ClientIp`) → `OrderedCorsFilter` → `BodySizeFilter` → `JwtPreCheckFilter` (dexter-security `JwtVerifier`, audience `gateway`) → `RateLimitFilter` (`RedisTokenBucket`, Lua); `RouteMatcher`; `ActivityPublisher` (Kafka, bounded queue, drop on full); `GatewayErrorHandler` (ProblemDetails); `Problems` |
| `config` | `GatewayConfiguration` (SCG routes from the table, breaker per service via Resilience4j customizer, per-route connect/response timeouts, SSE without both), `GatewayProperties` (`dexter.gateway.*`) |
| identity | audience `gateway` in every token; `server.forward-headers-strategy=native` (client IP behind the gateway) |
| contracts | `gateway.activity.recorded` v1 (`ActivityRecordedV1`) |
| Tests | unit `RouteDocsTest` 5, `ClientIpTest` 2, `GatewayArchitectureTest` 2, `GatewayHealthSmokeTest` 1; IT `RouteTableIT` 4, `EdgeFiltersIT` 7, `RateLimitIT` 3, `ResilienceIT` 3, `ActivityIT` 1, `DegradedIT` 1 (WireMock 3.13.2 backends, Redis with the `gateway` ACL user, Kafka) |
| Trail / smoke | `test-ui/p06-gateway.html`; `Trail.liveRoutes` emptied (all Live calls via the gateway); trail-check P04/P05 via `/api` + P06 checks; `scripts/lib/p06_smoke.py`; activity offsets check; k6 smoke checks the gateway's `X-Request-Id` echo |

**Query→Index (P06):** none (no database). Redis keys: `gateway:rl:<class>:<a|i>:<account|ip>` (string, PX ≥ 61 s).

**P05 Identity B (DONE)** — ADR-0010

| Piece | What it does |
|---|---|
| Schema `V202610171000__identity_mfa_admin` | `mfa_totp` (DEK-encrypted secret, `last_used_step`), `recovery_codes` (keyed hash, `used_at`), RBAC catalogue `roles`/`permissions`/`role_permissions` (data; SUPER_ADMIN non-standing), `admins` (+ `break_glass_eligible`, MFA CHECK), `admin_roles`, `admin_mfa_totp` (KEK-wrapped), `elevations` (reason 20–500, ≤ 1 h CHECK, RLS refuses ineligible), `admin_sessions`, `admin_refresh_tokens`, `admin_invites`, `admin_audit` (append-only). FORCE RLS everywhere; `identity_admin` reads by actor role with column grants; definer functions `auth_find_admin_login`, `auth_find_admin_refresh`, `admin_accept_invite` (app) and audited `admin_create` / `admin_update` (admin, `DX4xx` SQLSTATEs); `auth_find_login` reports MFA |
| `domain` | `Totp` (RFC 6238/4226, Base32, otpauth URI), `RecoveryCodes`, `ThrottlePolicy`, `AdminPermissions`, new `IdentityErrors` |
| `app` | `MfaService` (enrol/confirm/disable/regenerate), `UserSecondFactor`, `AuthService` (two-step login, `verifyMfa`, `stepUp`, lockout alerts), `Throttles` + `LoginThrottle` port, `MfaChallenges` port, `AdminAuthService` (login → setup/verify → admin tokens, refresh rotation + reuse, logout, invite accept), `ElevationService` (break-glass start/end + server-side re-check), `AdminDirectoryService` (list/get/invite/update/lookup, audited), `AdminGuard`, `SecretSealer`, `SecuritySettings` |
| `infra` | `RedisLoginThrottle` + `RedisMfaChallenges` (Lua, `identity:*` keys, fail closed via `RedisCalls`), `MfaRepository`, `AdminRepository` (app pool), `AdminDirectoryRepository` (admin pool, keyset, SQLSTATE mapping), `KekSecretSealer` |
| `api` / `config` | `MfaController`, `AdminAuthController`, `AdminController`, `AuthController` (+ `/mfa/verify`, `/step-up`); `IdentitySecurityConfiguration` (`identity_admin` Hikari pool held in `AdminPool`, not a DataSource bean), `dexter.identity.security.*` / `admin.*` properties, Redis (`spring.data.redis`, ACL user `identity`) |
| contracts | `identity.security.alert` v2 (A-38), `identity.admin.action` v1 |
| Tests | unit `TotpTest`, `RecoveryCodesTest`, `ThrottlePolicyTest`; IT `RedisLoginThrottleIT`, `MfaFlowIT`, `LockoutIT` (own context via `IdentityBase`), `AdminIT`, `AdminRlsIT`, `IdentityRlsIT` + mfa tables |
| Trail / smoke | `test-ui/p05-mfa-admin.html` (built-in WebCrypto authenticator, Mock emulates the rules), `liveRoutes` `/api/v1/admin/`, `scripts/lib/p05_smoke.py`, trail-check P05, `admin` topic check |

**Endpoints added in P05 (identity :8081)**

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/v1/auth/login` | public | MFA enrolled → `{mfaRequired, mfaToken, expiresIn}`; throttled (429) |
| POST | `/v1/auth/mfa/verify` | public (mfaToken) | `code` or `recoveryCode` → tokens; 5 wrong codes burn the challenge |
| POST | `/v1/auth/step-up` | user | password (+ second factor) → fresh access token |
| GET | `/v1/me/mfa` | user | status, pending, recovery codes left |
| POST | `/v1/me/mfa/totp/enroll` | user + step-up | pending secret + otpauth URI (no-store) |
| POST | `/v1/me/mfa/totp/confirm` | user | first code → 10 recovery codes (once) |
| DELETE | `/v1/me/mfa/totp` | user + step-up + amr mfa | 204 |
| POST | `/v1/me/mfa/recovery-codes` | user + step-up + amr mfa | new 10 codes |
| POST | `/v1/admin/auth/login` | public | always a challenge (`setup` on first sign-in) |
| POST | `/v1/admin/auth/mfa/setup`, `/mfa/verify` | public (mfaToken) | secret / admin tokens |
| POST | `/v1/admin/auth/token/refresh`, `/logout`, `/invite/accept` | refresh / admin / invite token | rotation + reuse; single-use invite |
| GET | `/v1/admin/me` | admin | roles, permissions, elevated |
| POST / DELETE | `/v1/admin/elevations`, `/v1/admin/elevations/current` | admin (eligible) | break-glass ≤ 1 h, reason + fresh code |
| GET | `/v1/admin/admins` (keyset), `/v1/admin/admins/{id}` (ETag) | `admins:read` + elevation | |
| POST / PATCH | `/v1/admin/admins`, `/v1/admin/admins/{id}` (If-Match) | `admins:write` + elevation | invite mail; disable/roles/resetMfa; never self |
| GET | `/v1/admin/accounts?email=` | `accounts:read` | audited (also when not found) |

**Events added:** `dexter.identity.security.v1` now carries `identity.security.alert` **v2** (+ `ACCOUNT_LOCKED`,
`MFA_ENABLED`, `MFA_DISABLED`, `RECOVERY_CODE_USED`, `RECOVERY_CODES_REGENERATED`); `dexter.identity.admin.v1`:
`identity.admin.action` v1 (key = affected account, else the acting admin) → audit (P18).

**Query→Index (P05)** (also in the migration header)

| Where | SQL shape | Index | Proof |
|---|---|---|---|
| MFA state / verify / replay guard | `mfa_totp WHERE account_id = :a` (+ `last_used_step < :s`) | `mfa_totp_pkey` | `MfaFlowIT` |
| recovery code use | `WHERE account_id = :a AND code_hash = :h AND used_at IS NULL` | `recovery_codes_account_id_code_hash_key` | `MfaFlowIT` |
| codes left | `WHERE account_id = :a AND used_at IS NULL` | `recovery_codes_unused` (partial) | `MfaFlowIT` |
| admin login | `auth_find_admin_login(email)` | `admins_email_key` | `AdminIT` |
| roles → permissions | `admin_roles WHERE admin_id`; `role_permissions WHERE role = ANY(:r)` | pkeys | `AdminIT` |
| active elevation | `WHERE admin_id = :a AND revoked_at IS NULL AND expires_at > now` | `elevations_active` (partial) | `AdminIT` |
| admin refresh lookup / rotate / reuse | as P04 on `admin_refresh_tokens` | `_token_hash_key`, pkey, `_family`, `_session` | `AdminIT.adminRefreshRotatesAndDetectsReuse` |
| GET /v1/admin/admins | `ORDER BY created_at DESC, id DESC LIMIT n+1` | `admins_list` | `AdminIT` (tiny table; no PlanAssert) |
| account lookup | `accounts WHERE email = :e` | `accounts_email_key` | `AdminIT` |
| throttles / challenges | Redis keys `identity:thr:<key>:{n,t,l}`, `identity:mfa:<sha256>` | — | `RedisLoginThrottleIT` |

**P04 Identity A (DONE)**

| Piece | What it does |
|---|---|
| Schema (`services/identity/src/main/resources/db/migration`) | `V202610170900__platform` = the dexter-db templates rendered for `identity` (drift-guarded by `PlatformMigrationTest`); `V202610170901__identity_core`: `accounts` (citext email, encrypted phone + blind index, status, tz, tier_cache, version), `account_keys` (wrapped per-account DEK), `verification_tokens`, `sessions`, `refresh_tokens` (family, parent, sliding + absolute expiry), `signing_keys` (next/active/retired). ENABLE + FORCE RLS everywhere; tenant policies for `identity_app`; `<table>_owner` policies (ADR-0009 §2); six `SECURITY DEFINER` pre-auth functions (`auth_find_login`, `auth_register`, `auth_find_refresh`, `auth_verify_email`, `auth_reset_password`, `auth_find_account`), EXECUTE for `identity_app` only. Flyway runs as `identity_owner` |
| `domain` | `IdentityErrors`, `AccountStatus`, `Emails`, `PasswordPolicy` (10..128 code points, breached, not the email), `SecretTokens` (256-bit opaque, SHA-256 stored), `RefreshRotation` (rotate / reused / expired / revoked; sliding expiry capped), `RevokeReason`, `IdentityTenants` (SYSTEM + per-client pre-auth tenants), `Profiles` (IANA tz, display name, E.164 phone) |
| `app` | `RegistrationService` (Idempotency-Key per client, always 202, verify / "already registered" mail), `EmailVerificationService`, `AuthService` (login, refresh rotation + family reuse detection, logout, logout-all), `PasswordService` (forgot / reset / change), `ProfileService` (GET/PATCH /me, phone envelope-encrypted), `SessionService`, `SigningKeyService` (next → active → retired, advisory-locked rotation, JWKS snapshot), `AccessTokens` (A-18 claims), `ServiceTokenService` (client credentials), ports |
| `infra` | JDBC repositories (all `TenantScope.require()`), `PreAuthRepository` (definer functions, `CAST(:e AS citext)`), `Argon2Hasher` (OWASP baseline, semaphore bulkhead → 503), `BundledBreachedList` (NCSC top-100k ≥ 10 chars, see NOTICE), `Aead` / `KekRing` / `EnvelopeCipher` (AES-256-GCM, per-account DEK, HMAC blind index), `JoseKeyCrypto` (ES256 keys wrapped by the KEK), `AfterCommitMailer` + `SmtpTransport` / `MemoryTransport` |
| `api` | `AuthController`, `MeController`, `JwksController`, `OAuthTokenController`, dev-only `DevKeysController` (`@Profile("dev")`) |
| `config` | `IdentityProperties` (`dexter.identity.*`), `IdentityConfiguration` (wiring; own `JwtVerifier` fed from the key table), `ClientCredentialsArgumentResolver` (HTTP Basic for /oauth2/token), `IdentityRelayConfiguration` (relay as `identity_relay`, topics first, inbox + idempotency purge), `IdentityJobs` (key rotation check every 10 min) |
| Tests | unit: `DomainRulesTest` 5, `RefreshRotationTest` 2, `CryptoTest` 4 (token contract against the real `JwtVerifier`), `Argon2HasherTest` 3, `BundledBreachedListTest` 1, `ClientCredentialsArgumentResolverTest` 2, `PlatformMigrationTest` 1, `IdentityArchitectureTest` 1. ITs (Testcontainers PG17 + Flyway, real HTTP): `AuthFlowIT` 7, `EnumerationSafetyIT` 4, `JwksRotationIT` 2, `ServiceTokenIT` 2, `ProfileIT` 2, `IdentityRlsIT` 7, `IdentityHealthIT` 2, `RegistrationIdempotencyContractIT` 6 (5.5), `SessionsPaginationContractIT` 9 (4.5, PlanAssert on 30k sessions) |
| dexter-test | `TestPostgres.flywayDb` (roles + extensions, no templates: the service's Flyway owns the schema), `RlsProofKit` with a tenant column (`accounts.id`) |
| contracts | `identity.account.registered`, `identity.account.verified`, `identity.session.revoked`, `identity.security.alert` v1 (schemas + records + samples) |
| Trail / smoke | `test-ui/p04-identity.html` (Mock emulates identity incl. reuse detection; Live via `/svc/identity`), `trail.js` `liveRoutes` bridge (until P06), `scripts/lib/p04_smoke.py` (26 checks incl. Mailpit), outbox → Kafka check, trail-check P04 (JWKS, demo login, /me, bridge) |

**Endpoints (identity :8081)**

| Method | Path | Auth | Idempotency | Paging | Notes |
|---|---|---|---|---|---|
| POST | `/v1/auth/register` | public | **required** (per client) | — | always 202 `{"status":"accepted"}` |
| POST | `/v1/auth/verify-email` | public | natural (single-use token) | — | `verified` / `already_verified` |
| POST | `/v1/auth/login` | public | — | — | tokens, `Cache-Control: no-store` |
| POST | `/v1/auth/token/refresh` | public (refresh token) | rotation | — | reuse → 401 `REFRESH_TOKEN_REUSED`, family revoked |
| POST | `/v1/auth/logout` | user | idempotent | — | 204 |
| POST | `/v1/auth/logout-all` | user + **step-up** | idempotent | — | `{"sessionsRevoked":n}` |
| POST | `/v1/auth/password/forgot` | public | — | — | always 202 |
| POST | `/v1/auth/password/reset` | public (token) | natural | — | revokes every session |
| POST | `/v1/auth/password/change` | user + **step-up** | — | — | keeps this session, revokes others |
| GET | `/v1/me` | user | — | — | `ETag: "<version>"` |
| PATCH | `/v1/me` | user | If-Match **required** | — | merge patch: `displayName`, `tz`, `phone` |
| GET | `/v1/me/sessions` | user | — | keyset | open sessions, `current` flag |
| DELETE | `/v1/me/sessions/{id}` | user | idempotent | — | 204 / 404 |
| GET | `/.well-known/jwks.json` | public | — | — | next + active + retired, `max-age=300` |
| POST | `/oauth2/token` | HTTP Basic (service) | — | — | client credentials, 5-min token for one audience (A-18) |
| GET/POST | `/_dev/p04/keys`, `/_dev/p04/keys/rotate` | dev profile only | — | — | trail + smoke |

**Events (identity)**

| Topic | Type | Key | Consumers (planned) |
|---|---|---|---|
| `dexter.identity.account.v1` | `identity.account.registered` v1, `identity.account.verified` v1 | account id | audit (P18), billing (P16) |
| `dexter.identity.session.v1` | `identity.session.revoked` v1 | account id | audit (P18) |
| `dexter.identity.security.v1` | `identity.security.alert` v1 (`REFRESH_TOKEN_REUSE`) | account id | audit/notifications (P18) |

**Query→Index (P04)** (also in the migration header)

| Where | SQL shape | Index | Proof |
|---|---|---|---|
| login, register, forgot | `auth_*(email)`: `WHERE email = :e` | `accounts_email_key` (unique citext) | `AuthFlowIT`, `EnumerationSafetyIT` |
| verify / reset token | `WHERE token_hash = :h` | `verification_tokens_token_hash_key` | `AuthFlowIT` |
| mail throttle, supersede | `WHERE account_id = :a AND purpose = :p AND created_at > :t` | `verification_tokens_recent` | `auth_register` (PG16 run in sandbox) |
| /me, PATCH /me, password | `WHERE id = :id` | `accounts_pkey` | `ProfileIT` |
| refresh lookup | `auth_find_refresh(hash)`: `WHERE token_hash = :h` | `refresh_tokens_token_hash_key` | `AuthFlowIT` |
| refresh lock / rotate | `WHERE account_id = :a AND id = :id FOR UPDATE` (+ session by pk) | `refresh_tokens_pkey`, `sessions_pkey` | `AuthFlowIT` |
| reuse → revoke family | `WHERE account_id = :a AND family_id = :f` | `refresh_tokens_family` | `AuthFlowIT.reusingARotated…` |
| revoke by session(s) | `WHERE account_id = :a AND session_id = ANY(:s)` | `refresh_tokens_session` (also the FK index) | `AuthFlowIT` |
| GET /v1/me/sessions | `WHERE account_id = app_account() AND revoked_at IS NULL ORDER BY created_at DESC, id DESC LIMIT n+1` | `sessions_active_list` (partial) | `SessionsPaginationContractIT.listQueryUsesTheIndex` (PlanAssert, 30k-session tenant, first + next page) |
| logout-all / change: open sessions | `WHERE account_id = :a AND revoked_at IS NULL` | `sessions_active_list` (prefix) | `AuthFlowIT` |
| signing keys | full scan of a ≤ 4-row table | `signing_keys_pkey` | `JwksRotationIT` |

**P03 (DONE)**

| Piece | What it does |
|---|---|
| `seed/` (`dexter-seed`, executable jar `seed/target/seed.jar`) | `generate` / `load` / `checksum` (ADR-0008). SplitMix64 `Rng` with label forks, deterministic UUIDv7, `CopyWriter` (COPY text + SHA-256), `RefData` (v1 CSVs, seed-independent ids), `Generator` (streams account by account; exact totals; salary week, Diwali 2025, weekends, UPI/card mix, price points, rent, imports with `client_ref`, 1 % soft-deleted, 3 % edited; `expense_daily_totals`), `Passwords` (Argon2id once per distinct password), `Loader` (owner role; real table with FORCE off in-tx, else `seed_stage`), fixtures + `manifest.json/tsv` |
| `seed/data/v1/` | 48 categories, spend model (36 leaves), 357 merchants, 635 aliases, 10 fictional `MOCK_` issuers, 40 `MOCK_` cards, 103 reward rules, 10 milestones |
| Sizes | small: 2 demo × 500 expenses, 5 cards; medium: 10,000 / 1,200,000 / 30,000; large: 100,000 / 5,000,000 / 300,000 (+ admin, + 1.1 M daily-total rows on medium) |
| `scripts/seed.sh` | `small|medium|large [--seed N] [--regen] [--no-load]`, `check [size]`, `fixtures`; reuses an unchanged dataset; `SEED_MAX_SECONDS` (600) |
| Tests | `RngTest` 6 (SplitMix64 reference values), `CopyWriterTest` 3, `GeneratorTest` 10, `PasswordsTest` 2, `SeedCliTest` 3, `FixturesInSyncTest` 1, `SeedLoadIT` 4 |
| `test-ui/trail.js` | Complete kit: personas + `mountTenantSwitcher` (Live login → `/api/v1/auth/login`), mock router `collection`/`mockList` (limit 1..100, signed cursor, filter binding, limit+1, (key, id) DESC), `idempotent` (replay, 422, 400, in-flight join, 5xx leaves no key), mock outbox `events` + `mountEvents`, `Pager` (MASTER_PLAN 4.4) + `mountPager` debug panel, `mountIdempotencyTester`, `useSeed`/`loadSeed`. P00–P02 API unchanged |
| `test-ui/p03-harness.html`, `fixtures/seed/*.json`, `tests/pager.spec.js` | P03 page (tenant switch, 50/25/24 window, cursor checks, idempotency tester, events, catalog); generated fixtures; 18 node tests (4.5 contract on the mock router, 4.4 window, 5.5 idempotency) |
| `perf/k6/` | `lib/base.js` (request ids, `class` tags, login, `walkList` 50/25, `idempotentPost` same-key retries, `expectProblem`), `thresholds.js` (8.1 targets, smoke/full, `SHAPES`), `scenario.template.js`; `perf/smoke.js` uses them |
| ArchitectureTests | `<Svc>ArchitectureTest` in all 9 servlet services (`ArchRules.check`) + `GatewayArchitectureTest` (shared rules + no `Mono/Flux.block*`, no servlet API) |
| verify / CI | `static.trail-pager`; `full.seed-medium`, `full.seed-determinism`; trail-check P03 page + fixtures; devcontainer Node 22; `.env.example` `SEED_BULK_PASSWORD` |

**Seed tables (staged in `seed_stage` until the phase's migration exists)**

| DB | Table | Real table in | Medium rows |
|---|---|---|---|
| identity | accounts / admins | P04 / P05 | 10,000 / 1 |
| catalog | issuers, card_products, card_product_versions, reward_rules, milestones | P09 | 10, 40, 40, 103, 10 |
| portfolio | user_cards, catalog_products_proj | P11 | 30,000, 40 |
| ledger | categories, expenses, expense_daily_totals / merchants, merchant_aliases | P07 / P08 | 48, 1,200,000, 1,099,029 / 357, 635 |

**Query→Index (P03):** no service queries. The loader's own statements are `to_regclass`, `information_schema.columns`,
`pg_class.relforcerowsecurity` lookups and one `count(*)` per loaded table (dev only).

### Before P03

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
| 0008 | Seed data: deterministic generator, `seed_stage` before migrations, owner-role COPY with FORCE off in-tx, passwords hashed once | Accepted: proven by CI run 38922523673 (`SeedLoadIT`, `GeneratorTest`, `full.seed-medium`, `full.seed-determinism`) |
| 0011 | Gateway: route table as code + doc + test, WebFilter edge chain (404 before 401), Redis Lua token bucket (auth fails closed, rest open), trusted-proxy client IP, breaker per service except SSE, activity via bounded queue without outbox | Accepted: proven by CI run 39020903788 |
| 0010 | Identity B: RFC 6238 TOTP with step replay guard, keyed recovery codes, Redis Lua throttles failing closed, admins separate from accounts with mandatory MFA, RBAC as data, SUPER_ADMIN only via ≤ 1 h elevation re-checked per request, second `identity_admin` pool | Accepted: proven by CI run 39016105238 |
| 0009 | Identity: pre-auth SECURITY DEFINER functions + owner policies, synthetic tenants, password-free registration fingerprint, strict refresh-family reuse detection, next/active/retired ES256 keys, envelope encryption, after-commit mail, client-credentials endpoint | Accepted: proven by CI run 39003795722 (identity ITs, api-smoke P04) |
| 0012 | Ledger as the reference service: layers + ports, static keyset query per filter mask, daily totals maintained in the write transaction (IST day × root), merge PATCH + If-Match, soft delete + `:restore`, outbox event per write, admin tokens refused on user endpoints | Accepted: proven by CI run 39023459091 |
| 0013 | Ledger B: global merchants read-only for the app, resolver (variants → exact/compact/prefix → trigram 0.45 → typo; rules after), Redis alias cache outside transactions, bounded search without a trigram index on expenses, bulk with per-item outcome, CSV import via presigned upload + COPY into a temp table + derived client refs | Accepted: proven by CI run 39042585515 |
| 0014 | Catalog: published face + editor drafts + immutable versions (trigger), publish in one transaction (no-op by content hash), rule overlap impossible (EXCLUDE gist), snapshot = API = compacted event, public reads with a sentinel tenant, editor-only admin pool, search through an owner SECURITY DEFINER function | Accepted: proven by CI run 39046289166 |
| 0016 | Portfolio A: catalog as a version-guarded projection written only by the PROJECTOR actor, soft-removed cards with a live-only unique key, computed billing cycles with statements materialised on posting + an owner-function closer, rewards earned at record time on catalog terms into an append-only points ledger (FIFO expiry), auto-match as scored suggestions from both sides, exact reducing-balance EMI, consumers ensure their source topics | Accepted: proven by CI run 39703341828 |
| 0015 | Catalog ingestion: SSRF policy in two halves (URL rules, then every resolved address public; manual redirects re-checked; size/time/type caps), sources saved only when fetchable, crawl runs with a checkpoint per source + stale re-claim + politeness, content-hash change detection with snapshots in the object store, field-level diff (absent ≠ removed, merchant rules untouched), the promoter as the only writer with replay-safe approve, INGESTOR actor role | Accepted: proven by CI run 39109141399 |

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
| 2026-10-16 | P03 | A-24: until a table's migration exists, `seed.sh` loads its rows into `seed_stage.<table>` (owner-only schema, planned columns, dropped/recreated per load); once it exists, into the real table as `<svc>_owner` with `NO FORCE ROW LEVEL SECURITY` only inside the load transaction. `--replace` truncates (CASCADE). ADR-0008. | No service table exists in P03 (the task asked to decide this); FORCE makes RLS apply to the owner. | P04/P05/P07/P08/P09/P11: run `seed.sh small` after the first migration; update `seed/…/Tables` + `Generator` when columns differ (the loader names them). |
| 2026-10-16 | P03 | A-25 (revises A-19): `bcprov-jdk18on` is pinned in P03 at **1.85.2** (seed Argon2id). | The seed needs Argon2id now; 1.84 and older are affected by CVE-2026-5598. | P04 reuses the pin through spring-security-crypto. |
| 2026-10-16 | P03 | A-26: each service's `ArchitectureTest` depends on `dexter-test` (test scope) with **all transitive deps excluded**, plus `archunit`. The gateway uses the same rules plus "no `Mono/Flux.block*`, no servlet API". | `ArchRules` needs only ArchUnit; dexter-test's transitives (Testcontainers, Kafka, Redis, servlet) would change the services' Spring test contexts and put servlet classes on the reactive gateway. | A service that needs the other test kits (ServiceDb, contracts) declares dexter-test again without the exclusion in its own phase. |
| 2026-10-16 | P03 | A-27: `test-ui/tests/pager.spec.js` is plain `node --test` on the DOM-free parts of `trail.js` (no Playwright/npm), and runs in `verify.sh static` (`static.trail-pager`). | MASTER_PLAN P03 allows Node or Playwright; no dependency to pin, runs in every verify mode and CI job. | Pages' DOM code stays outside the spec; trail-check covers serving. |
| 2026-10-16 | P03 | A-28 (revises A-02): rest-assured and wiremock are pinned by their first user (P04 API tests / P06 gateway), not in P03. | P03 has no HTTP API test; A-02's own rule is "pin in the phase that first uses it". | P04, P06. |
| 2026-10-16 | P03 | A-29: trail Mock/Live paths are the planned gateway paths under `/api/v1/…` (`/api/v1/expenses`, `/api/v1/auth/login`, …); fixtures are generated by `seed/`, never edited; the event viewer's Live mode polls a per-page dev endpoint (`livePath`) and is a stub until a service emits events. | One mock router for every later page; Live mode starts working as each phase ships its endpoints. | P04+ pages call `Trail.loadSeed()` and the same paths. |
| 2026-10-17 | P04 | A-30: pre-authentication SQL is six `SECURITY DEFINER` functions (not only lookups: register, verify-email and reset are atomic functions), and every identity table carries a `<table>_owner` policy for `identity_owner` because FORCE RLS applies to the owner (ADR-0009 §1–2). | Atomic pre-auth writes without an account context; the owner could disable RLS anyway. | Later services needing pre-auth access (webhooks P16) follow the same pattern. |
| 2026-10-17 | P04 | A-31: pre-auth work runs in `TenantTx` with synthetic tenants (`SYSTEM` nil UUID; per-client `PREAUTH` UUIDv3 = the registration Idempotency-Key scope). The registration fingerprint covers `{email, displayName, tz, clientId}` — never the password. | Resolves the P02 gap "pre-auth idempotency scope"; a stored fast hash of a password would be guessable. | P05 admin login, P16 webhooks. |
| 2026-10-17 | P04 | A-32: login/registration take an optional `clientId` (public clients `dexter-app`, `dexter-web`, `dexter-trail`; default `dexter-app`) that becomes the token's `client_id`. `PATCH /v1/me` also edits `phone` (E.164, envelope-encrypted), not only tz and display name. | `client_id` is required by the A-18 contract; the phone exercises 7.4's column encryption now. | P05 adds admin clients. |
| 2026-10-17 | P04 | A-33: the service-token endpoint is `POST /oauth2/token` (A-18), not `/internal/v1/service-token`; dev clients for all 10 services share `SERVICE_CLIENT_SECRET` and may target any other service until P06+ tighten the pairs. | Matches dexter-security's `ServiceTokenClient` (IT-proven in P02). | P06+: per-pair audiences. |
| 2026-10-17 | P04 | A-34: services with their own Flyway use `TestPostgres.flywayDb(svc)` (roles + extensions, no templates) and copy the rendered templates verbatim into their first migration, guarded by a drift test (`PlatformMigrationTest`). `RlsProofKit` takes a tenant column. | Tests run the real migrations as `<svc>_owner`. | Every service from P07 on copies identity's pattern. |
| 2026-10-17 | P04 | A-35: verify.sh full gains `full.seed-small` after `full.services-up` (the small seed then lands in the real `identity.accounts`), and `run.sh` appends keys that later phases add to `.env.example` to an existing `.env`. | The medium seed runs before services exist (staging); the demo login needs real rows. | P05/P07/P09/P11 rely on the same step for their tables. |
| 2026-10-17 | P04 | A-36: trail Live calls to `/api/v1/auth/*`, `/api/v1/me*`, `/api/.well-known/*`, `/api/_dev/p04/*` are rewritten to `/svc/identity/...` (`Trail.liveRoutes`). | The gateway routes `/api/*` only in P06. | P06 empties `liveRoutes`. |
| 2026-10-17 | P04 | A-37: security email (verify, reset, already-registered, password-changed) is sent after commit, asynchronously and bounded, without a durable queue; flows are user-retryable (≤ 5 links per account and hour). | A durable notification queue is P18. | P18 may move these emails to the notification service. |
| 2026-10-17 | P05 | A-38: `identity.security.alert` moves to v2 (same topic) with lockout and MFA kinds; identity produces only v2 (there were no v1 consumers). | New kinds are a contract change (6.4). | P18 consumes v2. |
| 2026-10-17 | P05 | A-39: admins are a separate principal (`identity.admins`, own sessions/refresh tokens/client `dexter-admin`, `scope=admin`), not accounts with roles; user and admin tokens are refused on each other's endpoints. The admin sign-in always returns a challenge; a first sign-in includes TOTP setup. Admin invitations (email link, `POST /v1/admin/admins`) replace seeding further admins. | MFA mandatory for admins without a half-authenticated token state; no account data reachable by admin principals except through `identity_admin`. | P06 routes `/api/v1/admin/*`; P18 audit. |
| 2026-10-17 | P05 | A-40: SUPER_ADMIN is never a standing role: only `elevations` (eligible admin, reason, fresh TOTP, ≤ 1 h) grant it, the token is capped at the elevation end and SUPER_ADMIN use-cases re-read the row. The seeded `admin@demo.in` holds SUPPORT, CATALOG_EDITOR, FINANCE and is break-glass eligible. | MASTER_PLAN 7.2 time-boxed break-glass; ending early must be immediate. | Demo docs (§9) updated. |
| 2026-10-17 | P05 | A-41: login throttling lives in Redis (Lua, time as argument) and fails closed (503) when Redis is down; keys are an email hash, the principal id and the IP. Admin endpoints acting on others use a second pool as `identity_admin` with column grants. | 7.1 lockout must survive restarts and be shared across instances; 3.1 role separation. | P06 adds edge rate limits and trusted client IP. |
| 2026-10-17 | P06 | A-42: route ownership where the plan overlaps — `/v1/calc/**` → portfolio (planning's `POST /v1/calc/emi` is served by portfolio's calc library), `/v1/webhooks/affiliate/**` → commerce before `/v1/webhooks/**` → billing, notifications/privacy/admin audit+activity → audit, the rest of `/v1/admin/*` by owner (routes.md). | One owner per path; the gateway cannot route one path to two services. | P12/P13 implement accordingly. |
| 2026-10-17 | P06 | A-43: edge rules run as WebFilters (not per-route gateway filters), so unknown paths are 404 problems before any token check; CORS runs before the token check (preflights). Only breaker and timeouts are per route. Rate-limit key = class + verified account, else class + client IP (not both). | MASTER_PLAN P06 "fixes the old 404/401 bugs"; one bucket per caller and class keeps limits predictable. | P19 ZAP baseline. |
| 2026-10-17 | P06 | A-44: identity adds audience `gateway` to user/admin tokens; identity trusts `X-Forwarded-For` from Tomcat's internal proxies; the trail bridge (`liveRoutes`, A-36) is removed — trail-check's P04/P05 checks go through `/api`. Activity events use a plain producer (no outbox, gateway has no DB), type `gateway.activity.recorded`. | The gateway verifies with the shared verifier; client IP for throttles; one Live path. | P18 consumes activity. |
| 2026-10-17 | P07 | A-45: the daily-totals day is the IST calendar day (`Asia/Kolkata`), not the account's time zone; list `from`/`to` are IST dates too (`to` inclusive). | One fixed bucket key keeps the aggregate exact without rewriting rows when a profile time zone changes; all demo users are in India. | P13/P14 read daily totals with IST days; a per-account zone would need a rebuild job. |
| 2026-10-17 | P07 | A-46: PlanAssert runs on 1.2M rows generated inside `ExpensePlanIT` (`generate_series`, one 30k-row account, ANALYZE) instead of the loaded medium seed. | Plans must be proven inside the Maven IT with Testcontainers; the medium seed loads into staging before services exist. | Later PlanAssert ITs generate their own volume the same way. |
| 2026-10-17 | P07 | A-47: `GET /v1/expenses/daily-totals?from&to` added (≤ 366 days) for the trail and P13; card and merchant indexes end in `id DESC` (no incremental sort); category and date-range plans may sort (bounded); the categories policy needs a tenant context even for system rows. | Daily totals must be observable; plans without Sort; no anonymous reads of tenant tables. | routes.md example; P13 planning reads totals. |
| 2026-10-17 | P08 | A-48: no trigram index on `expenses.merchant_display`: search is bounded by the tenant + date btree (default 12 months, ≤ 366 days) and rechecks the text (ILIKE on display/raw merchant and note, word similarity ≥ 0.5). | A global GIN index covers every tenant's rows; the per-tenant range is what bounds the scan; PlanAssert proves expenses_list. | P19 may add a per-tenant search index if heavy accounts need it. |
| 2026-10-17 | P08 | A-49: the calendar's "upcoming liabilities" come through a `LiabilitySource` port that is empty until portfolio (P11, card bills/EMIs) and planning (P13) publish them; the response already has `upcoming` per day. | Those facts are owned by later services. | P11/P13 provide the source (event projection). |
| 2026-10-17 | P08 | A-50: import flow = `POST /v1/imports` (job + presigned PUT) → upload → `POST /v1/imports/{id}:start` → worker; jobs carry `duplicates`, `error_code/detail`, `attempts`, status `awaiting_upload/queued/running/completed/failed`; COPY goes into a transaction-scoped temp table (COPY FROM is refused on RLS tables; `ledger_app` gets TEMPORARY), then a second transaction merges; rows without `client_ref` get a content hash + occurrence number; fixed CSV header (`HEADER MATCH`); 10 MB / 50k rows; stale running jobs re-claimed after 10 min, 3 attempts. | The plan names the steps, not the protocol; re-import idempotency needs a natural key per row. | P12 statement parsing can reuse the job + staging pattern. |
| 2026-10-17 | P08 | A-51: "LRU cache of resolved aliases in Redis" = dexter-cache global scopes with TTL (1 h aliases, 10 min autocomplete), read before any transaction (`prefetch`); Redis' eviction policy is an ops setting (P19; the dev instance also holds throttle keys, so no allkeys-lru there). The resolver adds compact/prefix and a typo (edit distance) stage to the plan's pipeline, and rules run after the merchant is known (merchant-id rules need it). `OutboxWriter.appendAll` added for batched events; the S3 SigV4 signer lives in ledger until a second service needs it. | Measured accuracy (98.4 % on distorted names vs ~80 % with exact + trigram only); dexter-cache contract. | P10 bumps the merchant cache scopes; P12 moves SigV4 to a library. |
| 2026-09-22 | P01 | A-12: the P01 trail is backed by a dev-only lab controller in `dexter-web` (`/_dev/p01/*`, `@Profile("dev")`, guard test) instead of database-backed endpoints. | P01 has no service schema yet; the lab runs the real library code. Paging against a real table is P07. | P19 deletes it with the trail; `run.sh` defaults to the dev profile locally. |
| 2026-10-17 | P09 | A-52: a card has a draft table beside its published face; editor changes never touch what the public reads until publish; a new card is a draft until its first publish. Publish with an unchanged canonical document is a no-op (`changed: false`). | MASTER_PLAN names drafts and publish but not where a draft lives; idempotent publish avoids empty versions. | P10's review queue writes drafts and calls publish. |
| 2026-10-17 | P09 | A-53: extra event `catalog.card.retired` (last snapshot, status retired) on the same compacted topic, and `catalog.offer.changed` for offer create/withdraw (no edit endpoint yet). | Projections must learn about retirement; the plan lists only publish. | P11/P14 consume both. |
| 2026-10-17 | P09 | A-54: the reward-rule exclusion constraint also includes the merchant (NULLs coalesced), so a merchant rule and a category rule may coexist; versions are immutable by trigger (`DX409`), not only by grants. | Merchant-specific rates are in the seed; the owner bypasses grants. | — |
| 2026-10-17 | P09 | A-55: public reads run in a sentinel `PUBLIC` tenant on catalog_app (policies ignore the account); search runs through the owner's SECURITY DEFINER `catalog.search_published_cards`, because RLS stops the planner from using the trigram index for non-leakproof LIKE/`%`. | RLS is FORCE on every table; a seq scan per search does not scale. | Other services with trigram search under RLS reuse the pattern (ADR-0014 §6). |
| 2026-10-17 | P09 | A-56: the admin flow is proven in ITs (editor tokens from TestJwt); api-smoke covers only 401/403 for admin, since a real admin token needs the MFA login of P05. | Smoke has no TOTP device. | P19 may add an admin smoke with a scripted TOTP. |
| 2026-10-18 | P10 | A-57: the plan's tables gain operational columns: sources (`host`, `card_product_id`, soft delete, `next_run_at`/`last_run_at`, `version`), crawl_runs (`trigger`, `source_ids`, `attempts`, `claimed_at`, `error_code`), snapshots (`url`, `run_id`, `content_type`, `size_bytes`), review_items (`base_version`, `confidence`, `reason`, `version`). A run covers a list of sources; endpoints add `GET/PATCH/DELETE …/sources/{id}`, `POST …/sources/{id}/runs`, `GET …/runs[/{id}]`, `GET /v1/admin/review/{id}`. | Resumability, scheduling and the review screen need them; the plan names only the core columns. | — |
| 2026-10-18 | P10 | A-58: the worker is a system actor role `INGESTOR` on the catalog_admin pool (no token carries it): it reads published cards only, writes ingestion rows, and can neither write published data nor record a promotion. | "The promoter is the only writer" enforced by RLS, not only by code. | — |
| 2026-10-18 | P10 | A-59: an approve is refused while the card has unpublished draft edits (`DRAFT_PENDING`); `DELETE /v1/admin/catalog/cards/{id}/draft` discards them. An item whose card moved past its base version is stale (`REVIEW_STALE`). One pending item per card; a newer diff supersedes it, an identical one is ignored. | Otherwise the next publish of the stale draft would silently undo a promotion; one item per card keeps the queue reviewable. | — |
| 2026-10-18 | P10 | A-60: extractor `pattern-v1` reads HTML only (fees, waiver spend, APR, forex, fuel waiver, cashback per category); PDFs and images are fetched, hashed and stored for review by eye. Cron schedules are at most hourly. | No PDF/OCR dependency in P10. | A later extractor (PDF text, LLM-assisted) produces the same extraction shape. |
| 2026-10-22 | P11 | A-62: `ExpenseStateV1` (all `ledger.expense.*` v1 events) carries `merchant` (the display text) beside `merchantId`; changed in place because portfolio is its first consumer. | The matcher compares merchant text with the bank descriptor; ids alone miss unresolved merchants. | Later consumers read it as part of v1. |
| 2026-10-22 | P11 | A-63: `user_cards` unique key is partial (`WHERE status <> 'removed'`) and removing is soft (status `removed`, `removed_at`; reads answer 404, a second DELETE 204); statements gain debits/credits/txn count; `card_transactions` gain `kind` (purchase/fee/interest/emi debits; refund/payment/cashback credits) and `version`; links gain `method`/`decided_at`; savings summary gains spend/txn columns. | History must survive removal and a card can come back; reports and fees need the kind. | — |
| 2026-10-22 | P11 | A-64: the global catalog projection is written by the consumer with actor role `PROJECTOR` on the app pool (no token carries it); the seed writes the seeded catalog's projection rows (`projected_version 0`) because it loads catalog tables without events. | RLS on a global table, and a working dev stack right after `seed.sh`. | — |
| 2026-10-22 | P11 | A-65: endpoints add `POST /v1/rewards/ledger` (redeem / adjust with note) and `/v1/cards/{id}/transactions/{txnId}`; `DELETE /v1/emi-plans/{id}` closes a plan and PATCH can foreclose; the report is `?period=month&month=YYYY-MM` or `?period=year&year=YYYY`. Statement closing is a scheduled job over an owner `SECURITY DEFINER` function (cross-tenant). | The plan names the reads; users need to record redemptions and end plans. | P12 statement upload marks statements paid. |
| 2026-10-22 | P11 | A-66: path ids (card, plan) scope keyset lists but are not bound into the cursor; query filters are. | PaginationContract case 8: another tenant's cursor must still return only own rows. | — |
| 2026-10-22 | P11 | A-67: a consuming service's relay also ensures the topics it consumes (same specs as the producer) because topic auto-creation is off. | Consumers otherwise wait minutes for metadata on a fresh stack. | — |
| 2026-10-18 | P10 | A-61: S3 code moved to `libs/dexter-objectstore` (SigV4 + `S3Objects`) now that catalog is the second user (A-51 said P12). The P05 smoke saves the demo admin's TOTP secret to the git-ignored `.run/admin-totp` so later smokes sign in as a real admin (A-56's limit lifted for P10). | Two users of the same signer; the admin flow deserved an end-to-end smoke. | P12 uses the library for statements. |
| 2026-10-27 | P12 | A-68: P12 is split. **P12a** = statement upload → scan → ai-parser → review → confirm, the Python ai-parser, S3 identities. **P12b** = the calc library (`libs/portfolio-calc`): port of the old repo's engines (LedgerEngine, MinimumPaymentSimulator, ExitPath, …) with their tests as golden fixtures, `/v1/calc/*`. | The old repo (`dexter-enterprise-java-updated.zip`) is not in the P12 chat or the new repo; P12's DoD is "100 % of the old calc tests ported", which cannot be met by rewriting from memory. | P12b needs the zip uploaded; P13 (planning) uses the calc library, so P12b comes before P13. |
| 2026-10-27 | P12a | A-69: upload protocol = `POST /v1/statements/uploads` (Idempotency-Key; presigned PUT to a quarantine key, 15 min) → client PUT → `POST …/{id}:submit` (Idempotency-Key, **Mode B** with recovery point `scanned`) → parser → `GET …/{id}`, `GET …/{id}/lines` (keyset, line order) → `:confirm` (optional key, `excludeLines`) / `:discard`; `GET /v1/statements/uploads[?cardId=]` keyset newest first. The same file (SHA-256) is live once per card. Files are deleted after the decision. | The plan names the steps, not the protocol; the scan + clean copy is an external side effect, which is what Mode B is for. | P18 privacy erasure covers the staged lines. |
| 2026-10-27 | P12a | A-70: the ai-parser holds no object-store credentials and no database: the submitted event carries a 6-hour presigned GET on the internal endpoint + SHA-256 + size; the parser fetches only from allowed origins, without redirects. Its output id is UUIDv5(input id). Portfolio's relay declares `dexter.ai-parser.statement.v1` (the parser has no relay; auto-create is off). Unreadable files are results (`status failed`), never retries. | Least privilege for the one component that reads untrusted PDFs; redeliveries must dedupe in the inbox. | A real OCR/LLM extractor plugs in behind `Extractor` (same event). |
| 2026-10-27 | P12a | A-71: statement lines become card transactions through `TransactionService.write` (the manual record path, `source statement`), with `client_ref = stmt:<sha256(card, day, amount, direction, normalised description, occurrence)>`; the existing unique `card_transactions_client_ref` makes confirm idempotent per line across uploads and layouts. Lines post at 00:00 IST of their day. | One write path for statements, rewards, savings, events and auto-match; "idempotent by line hash" from the plan. | P14 reco reads the same `transaction.recorded` events. |
| 2026-10-27 | P12a | A-72: the object store runs with S3 identities rendered at every start from `OBJECTSTORE_ACCESS_KEY/_SECRET_KEY` (`infra/objectstore/render-s3.sh`, one dev identity); anonymous requests are refused; the healthcheck is `/healthz`. `dexter-objectstore` gains `delete` and `presignInternal`. `IdempotencyContract` gains `writesEvent()` (upload creation writes no event). | ADR-0003 deferred identities to P12; statements must never be public. | P19: per-service identities + bucket policies. |

## 7. Known gaps / tech debt (honest, never deleted silently)

- **P04 identity:**
  - No lockout and no rate limits yet (P05 Redis lockout, P06 edge limits). The "already registered" email is not
    throttled; verification and reset emails are (5 per account and hour).
  - Sessions record the socket address as `ip_first`: behind the gateway (P06) that is the gateway until it forwards a
    trusted client IP.
  - Strict reuse detection: a client that refreshes the same token twice concurrently (two tabs) is signed out.
    Apps must single-flight refreshes per device (ADR-0009 §5).
  - Access tokens stay valid until they expire (≤ 10 min) after logout or reuse revocation — stateless by design
    (MASTER_PLAN 7.1; no revocation list).
  - `mfa_enrolled` is always false and `amr` is `[pwd]` until P05; `tier` comes from `accounts.tier_cache` (billing
    keeps it current from P16).
  - No durable mail queue (A-37); a mail-server outage loses those emails (logged `MAIL_FAILED`).
  - The breached list holds the ≈ 9.1k NCSC top-100k entries that are ≥ 10 characters; no online HIBP k-anonymity
    check (offline by plan).
  - (P05 resolved: lockout, `mfa_enrolled`/`amr` real.)
- **P11 portfolio A:**
  - Statements are generated from recorded transactions only; payments (status paid/partially_paid/overdue) are not
    tracked until statement upload (P12).
  - Reward caps (`capPaise`, statement/month) and milestones in the catalog terms are not applied at earn time; the
    points value is the catalog's point value, not a redemption-partner rate.
  - Auto-match needs the expense to carry the card (`paymentMethod card` + `userCardId`); expenses without a card
    are not matched to card transactions.
  - Points expire by FIFO at read time; no `expire` entries are written yet (a job could write them; the balance is
    already correct).
  - The report's annual fee is pro-rated by months, not by the card's anniversary or fee waiver spend.
- **P10 catalog B:**
  - Fetching is plain HTTP GET: pages rendered by JavaScript, PDFs and images are stored but not extracted (A-60).
  - `pattern-v1` is tuned to common Indian issuer wording; unusual wording yields fewer fields (never wrong ones by
    design, but a misread is possible: every change goes through a human approve).
  - The snapshot bucket has no retention policy yet (content-addressed, so unchanged pages add nothing).
  - The DNS-rebinding guard relies on the JVM address cache (default 30 s TTL): the check and the connect use the
    same cached answer; an in-process pinned resolver would be stricter (P19 hardening).
  - Sitemap pages match cards by URL slug or exact normalised name only; new cards found on a sitemap are not
    created as drafts (an editor creates the card, the next run diffs it).
- **P09 catalog A:**
  - Offers have create/withdraw only; no edit and no per-user eligibility (P10/P17).
  - Search is not paged (top 20) and ranks by name similarity only.
  - `categories/potential` ranks by the rule rate, ignoring caps and point values (reco does the real math, P14).
  - No catalog cache yet: reads go to Postgres (fast on the published partial indexes); a Redis scope comes when a
    hot path needs it.
  - The admin flow has no browser page beyond the trail (the admin console is later).
- **P08 ledger B:**
  - Search has no index on text: a very heavy account searching a full year scans its rows of that year (bounded).
  - Calendar liabilities are empty until P11/P13 (A-49).
  - Merchant cache entries live up to 1 h after a merchant data change unless the writer bumps the scope (no writer
    exists before P10; a re-seed is visible after the TTL).
  - Dev SeaweedFS is anonymous: presigned URLs are signed but not verified until P12 configures S3 identities. The
    browser upload in Live mode needs the object store reachable from the browser (`OBJECTSTORE_PUBLIC_URL`).
  - Import rows are validated in Java after COPY (10k rows ≈ seconds); files are capped at 10 MB / 50k rows.
  - Rules apply to new expenses only (no re-categorisation of existing rows).
- **P07 ledger:**
  - Daily totals use the IST day for everyone (A-45); a user abroad sees late-evening expenses on the IST date.
  - The category filter's plan sorts (GiST cannot return keyset order); bounded by one account's subtree. A date
    range may also sort (bitmap scan of the range): the account comes from `app_account()`, so the planner estimates
    the table's average rows per account. Bounded by the range; revisit with per-account statistics if a heavy user
    pages a long range.
  - `categorization_rules` exists without an API (P08); `categorySource` is `user` or `resolver` with the default
    `general` until P08's resolver.
  - `userCardId` is not checked against portfolio's cards (P11 owns cards; a later consumer can validate).
  - No hard delete or purge of soft-deleted expenses yet (privacy erasure is P18).
- **P06 gateway:**
  - The gateway's JWT check is early rejection only; it does not check roles/permissions (services do).
  - Rate limits are per account *or* per IP (not both); a NAT'd anonymous crowd shares one `auth` bucket.
  - Activity events can be dropped (queue full, Kafka down) by design; no outbox.
  - SSE through the trail nginx needs `proxy_buffering off` when P15 ships streams.
  - CORS allowlist is dev-only (`localhost:8099`); prod origins are configured in P19.
- **P05 identity B:**
  - An admin or user access token stays valid ≤ 10 min after disable/logout; only SUPER_ADMIN is re-checked per
    request (sessions and refresh tokens are revoked at once).
  - (P06 resolved: identity sees the client IP behind the gateway via X-Forwarded-For from trusted proxies.)
  - Break-glass has no two-person approval (`approved_by` reserved); no admin UI beyond the trail page.
  - `GET /v1/admin/admins` has no PlanAssert (tiny table); audit read endpoints are P18.
  - Admin TOTP secrets are KEK-wrapped (no per-admin DEK); KEK rotation re-wrap not automated (as P04).
  - KEK, pepper, cursor key and client secrets are env values (dev values in `.env.example`); Vault Transit is P19.
    KEK rotation re-wrapping of existing DEKs/keys is not automated (new material uses the active KEK id).
  - The login timing test allows max(40 ms, 50 %) between medians on a shared CI runner; it proves the absence of the
    ~50 ms Argon2 gap, not constant time to the microsecond.
  - The relay's maintenance (topics, inbox + idempotency purge) runs on the leader every 10 min; there is no metric
    for unpublished outbox rows yet (P19).
  - `identity.flyway_schema_history` lives in the `identity` schema (owner-only, no RLS; not a tenant table).
  - Idempotency-Key is implemented on registration only (the phase's "Idem: REQ" endpoint). The other mutations are
    naturally idempotent (verify/reset: single-use tokens; logout, session revoke: no-ops when repeated) or must
    **not** store their response (login/refresh return secrets — a replay store would persist live tokens).
  - No events yet for password change/reset (besides the session revocations), profile changes or tz changes;
    consumers that need them (audit P18) add them as new event types.

- **P03 seed:**
  - The planned column lists (`Tables`) are a forward commitment. Each phase must reconcile them with its
    migration. The loader fails loudly; it never drops columns silently.
  - `identity.accounts` has no `display_name` column in the seed (MASTER_PLAN P04 lists none); the personas carry
    one in the fixtures only.
  - A load is one transaction **per table**, not per dataset. A failure midway leaves earlier tables loaded;
    re-run with `--replace`.
  - Bulk accounts share one password hash and a seeded salt (dev only, ADR-0008).
  - `large` (5 M) has not been run anywhere yet (P19). Medium is the only size proven on CI.
  - The data version is v1. Changing a CSV changes reference ids and every checksum; once P08/P09 hard-code ids,
    add `v2` instead of editing.
  - `expense_daily_totals` has ~0.9 rows per expense on medium (sparse per account/day/root): realistic, but P07
    should expect that ratio.
- **P03 trail kit:**
  - Live mode has nothing to call yet (login P04, expenses P07, catalog P09, cards P11), so it shows 404/401 and
    says so.
  - The mock cursor signature is FNV-1a, not HMAC. It is a mock only; the real codec is `KeysetCursorCodec`.
  - (P09 resolved: the mock collection sorts ASC too; the catalog mock is "name ASC" with filters.)
  - The pager spec does not cover the DOM (`mountPager` paint/IntersectionObserver). The jsdom render was a manual
    sandbox check, not a CI step.
- **P03 k6:** `walkList`/`idempotentPost`/`login` are not exercised until P04/P07 endpoints exist (smoke still
  checks gateway health only). The gateway `X-Request-Id` echo check is deferred to P06.

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
- ~~`seed.sh` is a stub (P03).~~ Done in P03; the demo accounts are seeded (staging until P04).

## 8. How to run & verify (Codespaces)

P04 additions (code in the repo; `git pull`):
```bash
./scripts/run.sh core && ./scripts/run.sh services     # run.sh adds the new IDENTITY_* / SERVICE_CLIENT_SECRET keys to .env
./scripts/seed.sh small                                 # after identity started: demo accounts land in identity.accounts
./scripts/run.sh trail                                  # port 8099 → p04-identity.html (Mock works without services)
# Live: register on the page, open Mailpit (port 8025), click the link (it opens the page with the token), verify, login.
./scripts/verify.sh full && ./scripts/trail-check.sh    # CI does the same: workflow verify-full, reports on branch verify-reports
```

P03 additions (code is in the repo; `git pull` instead of unzipping):
```bash
cp -n .env.example .env; grep -q SEED_BULK_PASSWORD .env || echo 'SEED_BULK_PASSWORD=Seed-Bulk-2026!' >> .env
./scripts/run.sh core
./scripts/seed.sh small                 # or medium (≈ 30 s), large (P19)
./scripts/seed.sh check medium          # determinism
node --test test-ui/tests/pager.spec.js
./scripts/verify.sh full && ./scripts/trail-check.sh   # CI does the same: workflow verify-full, reports on branch verify-reports
```
Trail: `./scripts/run.sh trail` → port 8099 → `p03-harness.html` (Mock mode works without services).

Earlier instructions (zip-based, pre-repo):

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

- `asha@demo.in` (PREMIUM, 3 cards, 500 expenses), `ravi@demo.in` (FREE, 2 cards, 500 expenses) in `identity.accounts`;
  `admin@demo.in` in `identity.admins`: SUPPORT + CATALOG_EDITOR + FINANCE, break-glass eligible (SUPER_ADMIN only
  via `POST /v1/admin/elevations`); the first console sign-in sets up TOTP (p05 trail page has an authenticator).
  Passwords: `DEMO_PASSWORD_*` in `.env.example`.
- Bulk seed users `u0000001@seed.test` … (medium: 9,998, large: 99,998), password `SEED_BULK_PASSWORD`.
- From P04 the users live in the real `identity.accounts` once `seed.sh small` ran after identity started (`verify.sh
  full` does it as `full.seed-small`); from P05 `admin@demo.in` is in the real `identity.admins`.
- Service clients (P04): every service name, secret `SERVICE_CLIENT_SECRET` from `.env`, via `POST /oauth2/token`.

## 10. Changelog (one line per response)

- 2026-10-22 P11 r1–r5: portfolio schema (cards, statements, transactions, links, EMI, points ledger, savings, two projections) with FORCE RLS, billing cycles + closer, rewards on catalog terms, auto-match (MatchScore + both-direction matcher via Kafka), EMI math, report, ADR-0016, A-62..A-67, p11 page/smoke/trail-check, gateway ResilienceIT flake fixed. CI run 39703341828: verify full + trail-check PASS → **P11 DONE**.
- 2026-10-18 P10 r1–r3: ingestion schema + RLS (INGESTOR), SSRF-safe fetcher, pattern extractor, field diff, resumable runs + scheduler + politeness, review queue + idempotent approve via the promoter, draft discard, dexter-objectstore library, ADR-0015, A-57..A-61, p10 page/fixture/smoke/trail-check. CI run 39109141399: verify full + trail-check PASS → **P10 DONE**.
- 2026-10-17 P09 r1–r2: catalog schema (issuers, cards, drafts, immutable versions, rules with EXCLUDE, milestones, offers) with FORCE RLS, public reads (keyset, filters, detail ETag, search via owner function, potential, offers), editor admin (drafts, publish/retire, offers), outbox relay + catalog events/contracts, projection IT, 50k PlanAssert, ADR-0014, A-52..A-56, p09 page/smoke/trail-check. CI run 39046289166: verify full + trail-check PASS → **P09 DONE**.
- 2026-10-17 P08 r1–r2: merchants/aliases, resolver + rules, search, bulk, CSV import (presigned S3 + COPY + worker), calendar, summary, OutboxWriter.appendAll, ADR-0013, A-48..A-51, p08 page/smoke/trail-check. CI run 39042585515: verify full + trail-check PASS → **P08 DONE**.
- 2026-10-17 P07 r2: daily-totals negative deltas via UPDATE, date-range plan test allows a bounded sort. CI run 39023459091: verify full + trail-check PASS → **P07 DONE**.
- 2026-10-17 P07 r1: ledger schema (categories, expenses, daily totals, rules) with FORCE RLS, expense CRUD (idempotent create, keyset list per filter mask, If-Match PATCH, soft delete/restore), daily totals in the write txn, categories, outbox events + contracts, 1.2M-row PlanAssert, ADR-0012, A-45..A-47, errors.md ledger, p07-ledger.html, p07 smoke, trail-check P07.
- 2026-10-17 P06 r1–r5: gateway (route table + routes.md + tests, edge WebFilters, JWT pre-check, Redis token-bucket limiter, breaker per service, SSE route, security headers, CORS, activity events), identity audience `gateway` + forwarded headers, trail bridge removed, ADR-0011, A-42..A-44, api-smoke P06, p06-gateway.html, trail-check P06. CI run 39020903788: verify full + trail-check PASS → **P06 DONE**.

- 2026-10-17 P05 r1–r3: TOTP MFA + recovery codes + step-up, Redis lockout (Lua, fail closed), admin console (separate principal, mandatory MFA, invite), RBAC as data, break-glass elevations, `identity_admin` pool, alert v2 + admin.action v1, ADR-0010, A-38..A-41, api-smoke P05, p05-mfa-admin.html, trail-check P05. CI run 39016105238: verify full + trail-check PASS → **P05 DONE**.

- 2026-10-17 P04 r1+r2: identity service (Flyway schema with RLS + definer pre-auth functions, register/verify/login/refresh with family reuse detection/logout/password flows, /me with ETag + encrypted phone, keyset sessions, JWKS next/active/retired, /oauth2/token), 4 identity events, ADR-0009, A-30..A-37, dexter-test `flywayDb` + RlsProofKit tenant column, api-smoke P04, `full.seed-small`, p04-identity.html + trail bridge. CI run 39003795722: 21/21 + trail-check PASS → **P04 DONE**.
- 2026-10-16 P03 r1: seed generator `seed/` (ADR-0008, A-24/A-25), reference data v1 (40 MOCK_ cards, 357 merchants), `seed.sh`, generated trail fixtures; complete `trail.js` + `p03-harness.html` + `pager.spec.js` (A-27, A-29); `perf/k6` base + thresholds; ArchitectureTest in all 10 services (A-26); verify `static.trail-pager`, `full.seed-medium`, `full.seed-determinism`; trail-check P03. CI run 38922523673: 19/19 + trail-check PASS → **P03 DONE**.

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
