# verify-report (full)
**Generated:** 2026-10-31 18:09:06Z · **Overall:** PASS
Upload this file into the next Dexter chat (MASTER_PLAN 0.1).

## Environment
```
mode: full
git: 88a3cb8
os: Linux 7.0.0-1017-azure x86_64
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
| static.forbidden-patterns | PASS | forbidden pattern hits: 0 |
| static.migration-names | PASS | migrations checked: 16, bad: 0 |
| static.rls-gate | PASS | rls_check: 6 service migration set(s) checked, OK |
| static.trail-isolation | PASS | no service or lib references test-ui |
| static.skeletons | PASS | module/service skeleton problems: 0 |
| static.poms-wellformed | PASS | poms parsed: 25, malformed: 0 |
| static.shell-syntax | PASS | shell syntax errors: 0 |
| static.readme | PASS | BUILD_README status table present |
| static.trail-pager | PASS | pager.spec.js (node v24.22.1): PASS |
| static.compose-config | PASS | compose config valid |
| full.env-file | PASS |  |
| full.images-pull | PASS | core images ready: 5/5 |
| full.infra-up | PASS | core infra healthy |
| full.infra-checks | PASS | kafka: topics API reachable |
| full.maven | PASS | [INFO] ------------------------------------------------------------------------ |
| full.ai-parser | PASS | 72 passed in 0.80s |
| full.seed-medium | PASS | seed.sh: medium seed=42 generated in 7s, total 18s (limit 600s) |
| full.seed-determinism | PASS | seed deterministic: medium seed=42 checksum 08da35b601c5441ce952655b81aa3a0e80fa57ca46b92fab30d1f4a0c1fd676c |
| full.services-up | PASS | ai-parser UP on 8090 |
| full.seed-small | PASS | seed.sh: small seed=42 generated in 1s, total 2s (limit 600s) |
| full.api-smoke | PASS | # summary: PASS |
| full.k6-smoke | PASS | k6 smoke thresholds met |

## Modules (Maven reactor)
| Module | Result | Time |
|---|---|---|
| dexter-platform-bom | SUCCESS | 0.002 s |
| dexter-v2-parent | SUCCESS | 0.806 s |
| dexter-core | SUCCESS | 7.319 s |
| dexter-db | SUCCESS | 16.054 s |
| dexter-web | SUCCESS | 4.776 s |
| dexter-idempotency | SUCCESS | 4.360 s |
| dexter-events | SUCCESS | 4.747 s |
| dexter-cache | SUCCESS | 4.388 s |
| dexter-security | SUCCESS | 4.054 s |
| dexter-objectstore | SUCCESS | 1.365 s |
| dexter-contracts | SUCCESS | 2.179 s |
| dexter-test | SUCCESS | 38.506 s |
| dexter-lab | SUCCESS | 4.512 s |
| portfolio-calc | SUCCESS | 2.177 s |
| dexter-seed | SUCCESS | 13.234 s |
| dexter-gateway | SUCCESS | 01:02 min |
| dexter-identity | SUCCESS | 34.413 s |
| dexter-ledger | SUCCESS | 01:15 min |
| dexter-catalog | SUCCESS | 33.380 s |
| dexter-portfolio | SUCCESS | 43.216 s |
| dexter-planning | SUCCESS | 24.879 s |
| dexter-reco | SUCCESS | 20.221 s |
| dexter-billing | SUCCESS | 7.736 s |
| dexter-commerce | SUCCESS | 8.493 s |
| dexter-audit | SUCCESS | 8.643 s |

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
| libs/dexter-security | unit | 11 | 0 | 0 | 0 |
| libs/dexter-test | IT | 65 | 0 | 0 | 0 |
| libs/dexter-test | unit | 48 | 0 | 0 | 0 |
| libs/dexter-web | unit | 12 | 0 | 0 | 0 |
| libs/portfolio-calc | unit | 44 | 0 | 0 | 0 |
| seed | IT | 4 | 0 | 0 | 0 |
| seed | unit | 25 | 0 | 0 | 0 |
| services/audit | unit | 2 | 0 | 0 | 0 |
| services/billing | unit | 2 | 0 | 0 | 0 |
| services/catalog | IT | 27 | 0 | 0 | 0 |
| services/catalog | unit | 21 | 0 | 0 | 0 |
| services/commerce | unit | 2 | 0 | 0 | 0 |
| services/gateway | IT | 19 | 0 | 0 | 0 |
| services/gateway | unit | 10 | 0 | 0 | 0 |
| services/identity | IT | 65 | 0 | 0 | 0 |
| services/identity | unit | 33 | 0 | 0 | 0 |
| services/ledger | IT | 50 | 0 | 0 | 0 |
| services/ledger | unit | 10 | 0 | 0 | 0 |
| services/planning | IT | 53 | 0 | 0 | 0 |
| services/planning | unit | 28 | 0 | 0 | 0 |
| services/portfolio | IT | 84 | 0 | 0 | 0 |
| services/portfolio | unit | 15 | 0 | 0 | 0 |
| services/reco | IT | 37 | 0 | 0 | 0 |
| services/reco | unit | 41 | 0 | 0 | 0 |

## Containers (docker compose ps)
```
NAME                   IMAGE                      COMMAND                  SERVICE       CREATED          STATUS                   PORTS
dexter-kafka-1         apache/kafka:4.1.0         "/__cacert_entrypoin…"   kafka         15 seconds ago   Up 7 seconds (healthy)   127.0.0.1:9092->9092/tcp
dexter-mailpit-1       axllent/mailpit:v1.30.5    "/mailpit"               mailpit       15 seconds ago   Up 7 seconds (healthy)   127.0.0.1:1025->1025/tcp, 127.0.0.1:8025->8025/tcp, 1110/tcp
dexter-objectstore-1   chrislusf/seaweedfs:4.22   "/bin/sh -c 'sh /opt…"   objectstore   15 seconds ago   Up 7 seconds (healthy)   7333/tcp, 8080/tcp, 8888/tcp, 9333/tcp, 18080/tcp, 18888/tcp, 19333/tcp, 127.0.0.1:8333->8333/tcp
dexter-postgres-1      postgres:17                "docker-entrypoint.s…"   postgres      15 seconds ago   Up 7 seconds (healthy)   127.0.0.1:5432->5432/tcp
dexter-redis-1         redis:7.4-alpine           "docker-entrypoint.s…"   redis         15 seconds ago   Up 7 seconds (healthy)   127.0.0.1:6379->6379/tcp
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
PASS  gateway :8080  health UP  15ms
PASS  identity :8081  health UP  16ms
PASS  ledger :8082  health UP  13ms
PASS  catalog :8083  health UP  12ms
PASS  portfolio :8084  health UP  12ms
PASS  planning :8085  health UP  13ms
PASS  reco :8086  health UP  12ms
PASS  billing :8087  health UP  12ms
PASS  commerce :8088  health UP  12ms
PASS  audit :8089  health UP  13ms
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
... (392 more lines)
```

## k6 smoke
- requests: 34876  rate/s: 581.2442166512931
- http_req_duration p95: 3.3784892500000003 ms  max: 31.204657 ms
- http_req_failed rate: 0
