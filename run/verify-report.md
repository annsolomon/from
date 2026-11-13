# verify-report (full)
**Generated:** 2026-11-13 09:01:56Z · **Overall:** PASS
Upload this file into the next Dexter chat (MASTER_PLAN 0.1).

## Environment
```
mode: full
git: da3af6b
os: Linux 7.0.0-1019-azure x86_64
java: openjdk version "25.0.5" 2026-10-20 LTS
maven: Apache Maven 3.9.15 (98b2cdbfdb5f1ac8781f537ea9acccaed7922349)
docker: 29.4.2
compose: 5.1.3
k6: missing
python: Python 3.14.4
```

## Steps
| Step | Result | Detail |
|---|---|---|
| static.forbidden-patterns | PASS | forbidden pattern hits: 0 [0s] |
| static.migration-names | PASS | migrations checked: 35, bad: 0 [0s] |
| static.rls-gate | PASS | rls_check: 9 service migration set(s) checked, OK [0s] |
| static.trail-isolation | PASS | no service or lib references test-ui [0s] |
| static.skeletons | PASS | module/service skeleton problems: 0 [0s] |
| static.poms-wellformed | PASS | poms parsed: 27, malformed: 0 [0s] |
| static.shell-syntax | PASS | shell syntax errors: 0 [0s] |
| static.readme | PASS | BUILD_README status table present [0s] |
| static.trail-pager | PASS | pager.spec.js (node v24.22.2): PASS [1s] |
| static.obs-config | PASS | dashboards + promtool rules/config OK [4s] |
| static.release-guard | PASS | release guard (main, not a release branch): sees 15 trail/dev items (expected off release) [0s] |
| static.compose-config | PASS | compose config valid [0s] |
| full.env-file | PASS |  [0s] |
| full.images-pull | PASS | core images ready: 6/6 [14s] |
| full.infra-up | PASS | core infra healthy [12s] |
| full.infra-checks | PASS | kafka: topics API reachable [3s] |
| full.maven | PASS | [INFO] ------------------------------------------------------------------------ [646s] |
| full.ai-parser | PASS | 72 passed in 0.83s [8s] |
| full.seed-medium | PASS | seed.sh: medium seed=42 generated in 8s, total 18s (limit 600s) [18s] |
| full.seed-determinism | PASS | seed deterministic: medium seed=42 checksum 08da35b601c5441ce952655b81aa3a0e80fa57ca46b92fab30d1f4a0c1fd676c [9s] |
| full.obs-up | PASS |  Container dexter-grafana-1 Healthy  [21s] |
| full.services-up | PASS | ai-parser UP on 8090 [60s] |
| full.seed-small | PASS | seed.sh: small seed=42 generated in 2s, total 4s (limit 600s) [4s] |
| full.api-smoke | PASS | # summary: PASS [131s] |
| full.vault-check | PASS | vault check: 6 PASS / 0 FAIL [0s] |
| full.k6-smoke | PASS | k6 smoke thresholds met [30s] |
| full.obs-check | PASS | obs: 13 PASS / 0 FAIL; dlq replay ok [3s] |
| full.zap-baseline | PASS | zap: 34 URLs scanned, 2 alert types (10049:Informational, 90004:Low), 0 FAIL-level, exit 0 [49s] |
| full.chaos | PASS | chaos: 16 PASS / 0 FAIL [130s] |
| full.restore-drill | PASS | restore drill: 9/9 databases restored identically, 5583 rows; PASS [41s] |
| full.image-scan | PASS | images: 10/10 built; findings in target/verify/trivy/summary.tsv; clean [124s] |

## Modules (Maven reactor)
| Module | Result | Time |
|---|---|---|
| dexter-platform-bom | SUCCESS | 0.002 s |
| dexter-v2-parent | SUCCESS | 0.569 s |
| dexter-core | SUCCESS | 5.862 s |
| dexter-db | SUCCESS | 15.710 s |
| dexter-web | SUCCESS | 4.730 s |
| dexter-idempotency | SUCCESS | 5.971 s |
| dexter-events | SUCCESS | 5.509 s |
| dexter-cache | SUCCESS | 4.183 s |
| dexter-security | SUCCESS | 5.049 s |
| dexter-objectstore | SUCCESS | 1.703 s |
| dexter-contracts | SUCCESS | 2.586 s |
| dexter-privacy | SUCCESS | 2.235 s |
| dexter-observability | SUCCESS | 2.400 s |
| dexter-test | SUCCESS | 01:10 min |
| dexter-lab | SUCCESS | 5.328 s |
| portfolio-calc | SUCCESS | 2.433 s |
| dexter-seed | SUCCESS | 15.568 s |
| dexter-gateway | SUCCESS | 01:10 min |
| dexter-identity | SUCCESS | 42.634 s |
| dexter-ledger | SUCCESS | 01:32 min |
| dexter-catalog | SUCCESS | 37.912 s |
| dexter-portfolio | SUCCESS | 52.561 s |
| dexter-planning | SUCCESS | 27.059 s |
| dexter-reco | SUCCESS | 29.390 s |
| dexter-billing | SUCCESS | 41.948 s |
| dexter-commerce | SUCCESS | 37.027 s |
| dexter-audit | SUCCESS | 01:00 min |

## Tests
| Module | Kind | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|---|
| contracts | unit | 3 | 0 | 0 | 0 |
| libs/dexter-cache | unit | 17 | 0 | 0 | 0 |
| libs/dexter-core | unit | 28 | 0 | 0 | 0 |
| libs/dexter-db | IT | 33 | 0 | 0 | 0 |
| libs/dexter-db | unit | 20 | 0 | 0 | 0 |
| libs/dexter-events | unit | 12 | 0 | 0 | 0 |
| libs/dexter-idempotency | unit | 17 | 0 | 0 | 0 |
| libs/dexter-lab | unit | 18 | 0 | 0 | 0 |
| libs/dexter-objectstore | unit | 2 | 0 | 0 | 0 |
| libs/dexter-observability | unit | 2 | 0 | 0 | 0 |
| libs/dexter-privacy | unit | 4 | 0 | 0 | 0 |
| libs/dexter-security | unit | 11 | 0 | 0 | 0 |
| libs/dexter-test | IT | 68 | 0 | 0 | 0 |
| libs/dexter-test | unit | 48 | 0 | 0 | 0 |
| libs/dexter-web | unit | 12 | 0 | 0 | 0 |
| libs/portfolio-calc | unit | 44 | 0 | 0 | 0 |
| seed | IT | 4 | 0 | 0 | 0 |
| seed | unit | 25 | 0 | 0 | 0 |
| services/audit | IT | 28 | 0 | 0 | 0 |
| services/audit | unit | 9 | 0 | 0 | 0 |
| services/billing | IT | 52 | 0 | 0 | 0 |
| services/billing | unit | 12 | 0 | 0 | 0 |
| services/catalog | IT | 27 | 0 | 0 | 0 |
| services/catalog | unit | 21 | 0 | 0 | 0 |
| services/commerce | IT | 49 | 0 | 0 | 0 |
| services/commerce | unit | 18 | 0 | 0 | 0 |
| services/gateway | IT | 20 | 0 | 0 | 0 |
| services/gateway | unit | 10 | 0 | 0 | 0 |
| services/identity | IT | 76 | 0 | 0 | 0 |
| services/identity | unit | 33 | 0 | 0 | 0 |
| services/ledger | IT | 50 | 0 | 0 | 0 |
| services/ledger | unit | 10 | 0 | 0 | 0 |
| services/planning | IT | 53 | 0 | 0 | 0 |
| services/planning | unit | 28 | 0 | 0 | 0 |
| services/portfolio | IT | 84 | 0 | 0 | 0 |
| services/portfolio | unit | 15 | 0 | 0 | 0 |
| services/reco | IT | 59 | 0 | 0 | 0 |
| services/reco | unit | 69 | 0 | 0 | 0 |

## Containers (docker compose ps)
```
NAME                   IMAGE                      COMMAND                  SERVICE       CREATED          STATUS                   PORTS
dexter-kafka-1         apache/kafka:4.1.0         "/__cacert_entrypoin…"   kafka         11 seconds ago   Up 7 seconds (healthy)   127.0.0.1:9092->9092/tcp
dexter-mailpit-1       axllent/mailpit:v1.30.5    "/mailpit"               mailpit       11 seconds ago   Up 7 seconds (healthy)   127.0.0.1:1025->1025/tcp, 127.0.0.1:8025->8025/tcp, 1110/tcp
dexter-objectstore-1   chrislusf/seaweedfs:4.22   "/bin/sh -c 'sh /opt…"   objectstore   11 seconds ago   Up 7 seconds (healthy)   7333/tcp, 8080/tcp, 8888/tcp, 9333/tcp, 18080/tcp, 18888/tcp, 19333/tcp, 127.0.0.1:8333->8333/tcp
dexter-postgres-1      postgres:17                "docker-entrypoint.s…"   postgres      11 seconds ago   Up 7 seconds (healthy)   127.0.0.1:5432->5432/tcp
dexter-redis-1         redis:7.4-alpine           "docker-entrypoint.s…"   redis         11 seconds ago   Up 7 seconds (healthy)   127.0.0.1:6379->6379/tcp
dexter-vault-1         hashicorp/vault:2.0        "docker-entrypoint.s…"   vault         11 seconds ago   Up 7 seconds (healthy)   127.0.0.1:8200->8200/tcp
```

## Infra checks
```
service databases: 9 (expected 9)
service roles: 45 (expected 45)
non-superuser roles with row-security bypass: 0 (expected 0)
dev lab database (P02 trail lab): 1 (expected 1)
ledger_app settings: statement_timeout=5s; lock_timeout=2s; idle_in_transaction_session_timeout=10s; search_path=ledger, public
ledger_app login to ledger: OK
cross-db isolation: OK (ledger_app cannot connect to identity)
redis: PONG
redis acl users: 12 OK
redis ledger user, own prefix: OK
redis ledger user, 'GET gen:catalog:global:rules': refused OK
redis ledger user, 'SET catalog:v1:x 1': refused OK
redis ledger user, 'KEYS *': refused OK
redis ledger user, 'FLUSHALL': refused OK
redis ledger user, 'CONFIG GET maxmemory': refused OK
redis ledger user, 'DEL ledger:v1:acl-check': refused OK
objectstore: S3 endpoint up on :8333
objectstore: anonymous PUT refused (403) OK
kafka: topics API reachable
```

## API smoke
```
# P00 health
PASS  gateway :8080  health UP  21ms
PASS  identity :8081  health UP  29ms
PASS  ledger :8082  health UP  16ms
PASS  catalog :8083  health UP  16ms
PASS  portfolio :8084  health UP  16ms
PASS  planning :8085  health UP  15ms
PASS  reco :8086  health UP  18ms
PASS  billing :8087  health UP  18ms
PASS  commerce :8088  health UP  22ms
PASS  audit :8089  health UP  17ms
# P01 request id + ProblemDetail (9 servlet services)
PASS  identity  X-Request-Id echoed + generated, 404 ProblemDetail NOT_FOUND
PASS  ledger  X-Request-Id echoed + generated, 404 ProblemDetail NOT_FOUND
PASS  catalog  X-Request-Id echoed + generated, 404 ProblemDetail NOT_FOUND
PASS  portfolio  X-Request-Id echoed + generated, 404 ProblemDetail NOT_FOUND
PASS  planning  X-Request-Id echoed + generated, 404 ProblemDetail NOT_FOUND
PASS  reco  X-Request-Id echoed + generated, 404 ProblemDetail NOT_FOUND
PASS  billing  X-Request-Id echoed + generated, 404 ProblemDetail NOT_FOUND
PASS  commerce  X-Request-Id echoed + generated, 404 ProblemDetail NOT_FOUND
PASS  audit  X-Request-Id echoed + generated, 404 ProblemDetail NOT_FOUND
# P01 lab (ledger, dev profile)
PASS  money 500p x 50bps HALF_EVEN = 2  200
PASS  money HALF_UP = 3  200
PASS  strict json ok  200
PASS  unknown field  400
PASS  duplicate field  400
PASS  malformed json  400
PASS  validation errors[]  400
PASS  If-Match missing  428
PASS  If-Match weak  400
PASS  If-Match current  200
PASS  If-Match stale  412
PASS  limit=101  400
PASS  limit=0  400
PASS  garbage cursor  400
PASS  keyset walk 120 rows, no dups, last page nextCursor=null
PASS  cursor filter mismatch  400
PASS  tampered cursor  400
# P02 lab (ledger /_dev/p02)
PASS  lab starts (Postgres lab db, Kafka topics, relay, consumers, Redis user lab)
PASS  valid user token accepted (kind USER, account from sub)
PASS  no token -> 401 UNAUTHENTICATED
PASS  jwt expired -> 401 EXPIRED
PASS  jwt not-yet-valid -> 401 NOT_YET_VALID
PASS  jwt lifetime-too-long -> 401 LIFETIME
PASS  jwt wrong-audience -> 401 AUDIENCE
PASS  jwt wrong-issuer -> 401 ISSUER
PASS  jwt wrong-typ -> 401 TYPE
PASS  jwt hs256-confusion -> 401 ALGORITHM
PASS  jwt unknown-kid -> 401 KID_UNKNOWN
PASS  jwt embedded-jwk -> 401 HEADER
PASS  jwt bad-signature -> 401 SIGNATURE
PASS  jwt alg none -> 401
PASS  service token on a user endpoint -> 403 FORBIDDEN
PASS  key rotation: new kid accepted via JWKS refetch, old-kid token still valid
PASS  POST with key -> 201 + Location
PASS  same key again -> byte-identical replay + Idempotent-Replayed
PASS  same key, different body -> 422 IDEMPOTENCY_KEY_REUSED
PASS  no key -> 400 IDEMPOTENCY_KEY_REQUIRED
PASS  no token -> 401
PASS  same key while the first holds it -> 409 IDEMPOTENCY_IN_PROGRESS + Retry-After
PASS  the held request completes 201
PASS  outbox -> relay -> Kafka -> projector (inbox + version guard)
PASS  outbox rows marked published
PASS  poison event: retry-2s -> retry-5s -> DLQ with attempt/error headers
PASS  cached read: miss, then hit
PASS  rename (versioned) -> 200
PASS  after the write: miss with the new value (Path A after commit)
PASS  stale version -> 412 VERSION_CONFLICT
PASS  another tenant reading the id -> 404 (RLS + per-account keys)
PASS  cached keyset list
PASS  Redis down -> read bypasses to the DB (200, correct value)
PASS  Redis back -> hits again
PASS  cache keys of the account visible
PASS  privacy purge removes every cached entry of the account
# P02 summary: PASS
# P04 identity (:8081, Mailpit :8025)
PASS  jwks: 200, >= 2 public keys, no private parts
PASS  register: 202 accepted
... (615 more lines)
```

## k6 smoke
- requests: 59257  rate/s: 1974.685124869223
- http_req_duration p95: 10.6765332 ms  max: 65.305796 ms
- http_req_failed rate: 0
