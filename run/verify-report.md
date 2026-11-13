# verify-report (full)
**Generated:** 2026-11-13 08:57:55Z · **Overall:** PASS
Upload this file into the next Dexter chat (MASTER_PLAN 0.1).

## Environment
```
mode: full
git: 03b2e03
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
| static.poms-wellformed | PASS | poms parsed: 26, malformed: 0 [0s] |
| static.shell-syntax | PASS | shell syntax errors: 0 [0s] |
| static.readme | PASS | BUILD_README status table present [0s] |
| static.obs-config | PASS | dashboards + promtool rules/config OK [6s] |
| static.release-guard | PASS | release guard (release/v2.0): clean [0s] |
| static.compose-config | PASS | compose config valid [0s] |
| full.env-file | PASS |  [0s] |
| full.images-pull | PASS | core images ready: 6/6 [16s] |
| full.infra-up | PASS | core infra healthy [18s] |
| full.infra-checks | PASS | kafka: topics API reachable [3s] |
| full.maven | PASS | [INFO] ------------------------------------------------------------------------ [478s] |
| full.ai-parser | PASS | 72 passed in 0.71s [7s] |
| full.seed-medium | PASS | seed.sh: medium seed=42 generated in 5s, total 14s (limit 600s) [14s] |
| full.seed-determinism | PASS | seed deterministic: medium seed=42 checksum 08da35b601c5441ce952655b81aa3a0e80fa57ca46b92fab30d1f4a0c1fd676c [5s] |
| full.obs-up | PASS |  Container dexter-grafana-1 Healthy  [24s] |
| full.services-up | PASS | ai-parser UP on 8090 [37s] |
| full.seed-small | PASS | seed.sh: small seed=42 generated in 0s, total 1s (limit 600s) [1s] |
| full.api-smoke | PASS | # summary: PASS [122s] |
| full.vault-check | PASS | vault check: 6 PASS / 0 FAIL [1s] |
| full.k6-smoke | PASS | k6 smoke thresholds met [30s] |
| full.obs-check | PASS | obs: 13 PASS / 0 FAIL; dlq replay ok [1s] |
| full.zap-baseline | PASS | zap: 34 URLs scanned, 3 alert types (10049:Informational, 10049:Informational, 90004:Low), 0 FAIL-level, exit 0 [47s] |
| full.chaos | PASS | chaos: 16 PASS / 0 FAIL [108s] |
| full.restore-drill | PASS | restore drill: 9/9 databases restored identically, 5582 rows; PASS [29s] |
| full.image-scan | PASS | images: 10/10 built; findings in target/verify/trivy/summary.tsv; clean [119s] |

## Modules (Maven reactor)
| Module | Result | Time |
|---|---|---|
| dexter-platform-bom | SUCCESS | 0.002 s |
| dexter-v2-parent | SUCCESS | 0.604 s |
| dexter-core | SUCCESS | 5.560 s |
| dexter-db | SUCCESS | 12.534 s |
| dexter-web | SUCCESS | 2.221 s |
| dexter-idempotency | SUCCESS | 3.596 s |
| dexter-events | SUCCESS | 4.550 s |
| dexter-cache | SUCCESS | 3.116 s |
| dexter-security | SUCCESS | 3.226 s |
| dexter-objectstore | SUCCESS | 1.194 s |
| dexter-contracts | SUCCESS | 2.189 s |
| dexter-privacy | SUCCESS | 1.932 s |
| dexter-observability | SUCCESS | 1.371 s |
| dexter-test | SUCCESS | 01:03 min |
| portfolio-calc | SUCCESS | 1.866 s |
| dexter-seed | SUCCESS | 10.441 s |
| dexter-gateway | SUCCESS | 59.310 s |
| dexter-identity | SUCCESS | 30.438 s |
| dexter-ledger | SUCCESS | 01:03 min |
| dexter-catalog | SUCCESS | 27.265 s |
| dexter-portfolio | SUCCESS | 36.761 s |
| dexter-planning | SUCCESS | 20.275 s |
| dexter-reco | SUCCESS | 20.196 s |
| dexter-billing | SUCCESS | 28.584 s |
| dexter-commerce | SUCCESS | 26.651 s |
| dexter-audit | SUCCESS | 44.836 s |

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
| libs/dexter-objectstore | unit | 2 | 0 | 0 | 0 |
| libs/dexter-observability | unit | 2 | 0 | 0 | 0 |
| libs/dexter-privacy | unit | 4 | 0 | 0 | 0 |
| libs/dexter-security | unit | 11 | 0 | 0 | 0 |
| libs/dexter-test | IT | 68 | 0 | 0 | 0 |
| libs/dexter-test | unit | 48 | 0 | 0 | 0 |
| libs/dexter-web | unit | 4 | 0 | 0 | 0 |
| libs/portfolio-calc | unit | 44 | 0 | 0 | 0 |
| seed | IT | 4 | 0 | 0 | 0 |
| seed | unit | 24 | 0 | 0 | 0 |
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
| services/identity | IT | 75 | 0 | 0 | 0 |
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
NAME                   IMAGE                      COMMAND                  SERVICE       CREATED          STATUS                    PORTS
dexter-kafka-1         apache/kafka:4.1.0         "/__cacert_entrypoin…"   kafka         18 seconds ago   Up 10 seconds (healthy)   127.0.0.1:9092->9092/tcp
dexter-mailpit-1       axllent/mailpit:v1.30.5    "/mailpit"               mailpit       18 seconds ago   Up 10 seconds (healthy)   127.0.0.1:1025->1025/tcp, 127.0.0.1:8025->8025/tcp, 1110/tcp
dexter-objectstore-1   chrislusf/seaweedfs:4.22   "/bin/sh -c 'sh /opt…"   objectstore   18 seconds ago   Up 10 seconds (healthy)   7333/tcp, 8080/tcp, 8888/tcp, 9333/tcp, 18080/tcp, 18888/tcp, 19333/tcp, 127.0.0.1:8333->8333/tcp
dexter-postgres-1      postgres:17                "docker-entrypoint.s…"   postgres      18 seconds ago   Up 10 seconds (healthy)   127.0.0.1:5432->5432/tcp
dexter-redis-1         redis:7.4-alpine           "docker-entrypoint.s…"   redis         18 seconds ago   Up 10 seconds (healthy)   127.0.0.1:6379->6379/tcp
dexter-vault-1         hashicorp/vault:2.0        "docker-entrypoint.s…"   vault         18 seconds ago   Up 10 seconds (healthy)   127.0.0.1:8200->8200/tcp
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
PASS  gateway :8080  health UP  14ms
PASS  identity :8081  health UP  14ms
PASS  ledger :8082  health UP  12ms
PASS  catalog :8083  health UP  12ms
PASS  portfolio :8084  health UP  10ms
PASS  planning :8085  health UP  11ms
PASS  reco :8086  health UP  15ms
PASS  billing :8087  health UP  15ms
PASS  commerce :8088  health UP  14ms
PASS  audit :8089  health UP  11ms
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
# release: no dev endpoints
PASS  release: http://localhost:8082/_dev/p01/money?paise=500&bps=50  404 (nothing there)
PASS  release: http://localhost:8082/_dev/p02/status  404 (nothing there)
PASS  release: http://localhost:8081/_dev/p04/keys  404 (nothing there)
PASS  release: http://localhost:8080/_dev/p04/keys  404 (nothing there)
# P04 identity (:8081, Mailpit :8025)
PASS  jwks: 200, >= 2 public keys, no private parts
PASS  register: 202 accepted
PASS  register replay: 202 + Idempotent-Replayed
PASS  register without key: 400 IDEMPOTENCY_KEY_REQUIRED
PASS  register breached password: 400
PASS  login before verification: 403 EMAIL_NOT_VERIFIED
PASS  verification email arrived in Mailpit
PASS  verify-email: 200 verified
PASS  verify-email reused: 400 INVALID_TOKEN
PASS  login: 200 tokens, no-store
PASS  me: 200 with ETag
PASS  PATCH /me with If-Match: 200, phone normalised, new ETag
PASS  PATCH /me with a stale ETag: 412 VERSION_CONFLICT
PASS  refresh: 200, rotated
PASS  replay of the rotated refresh token: 401 REFRESH_TOKEN_REUSED
PASS  family revoked: the newest token is dead too (401)
PASS  sessions: only the new session is open, marked current
PASS  unknown email and wrong password answer identically (401 INVALID_CREDENTIALS)
PASS  forgot password: 202 for known and unknown emails
PASS  seeded demo account asha@demo.in signs in
PASS  client credentials ledger -> catalog: 200 Bearer, 300 s
PASS  client credentials with a wrong secret: 401 INVALID_CLIENT
PASS  release: the dev key-rotation endpoint is gone (rotation is the scheduled job)
PASS  token still accepted
PASS  logout: 204
PASS  refresh after logout: 401
# P04 smoke: PASS
# P05 identity MFA + admin (:8081)
PASS  user signed up and in
PASS  enroll: 200 secret + otpauth URI, no-store
PASS  confirm: 200 and 10 recovery codes
PASS  login with MFA: challenge, no tokens
PASS  replayed TOTP step: 401 INVALID_MFA_CODE
PASS  next TOTP step: 200 tokens, amr has mfa
PASS  recovery code: 200
PASS  same recovery code again: 401
PASS  step-up (password + recovery code): 200 fresh token
PASS  pre-MFA token cannot remove MFA: 401 STEP_UP_REQUIRED
PASS  MFA session removes MFA: 204
PASS  repeated failures are throttled: 429 RATE_LIMITED + Retry-After
PASS  DEMO_PASSWORD_ADMIN set
PASS  admin login: 200 challenge
PASS  admin MFA setup: secret + otpauth URI
PASS  admin verify: 200 admin tokens
PASS  admin token: scope admin, standing roles, permissions, amr mfa
PASS  account lookup (audited): 200 asha
PASS  admins list without break-glass: 403
PASS  admin token on a user endpoint: 403
PASS  break-glass: 200 SUPER_ADMIN token capped at the elevation
PASS  admins list while elevated: 200
PASS  invite admin: 201 invited
PASS  invite email arrived in Mailpit
PASS  accept invite: 200
... (564 more lines)
```

## k6 smoke
- requests: 82697  rate/s: 2756.3118603355083
- http_req_duration p95: 7.7182496 ms  max: 39.771979 ms
- http_req_failed rate: 0
