# Gateway routes (MASTER_PLAN P06)

The gateway (`:8080`, behind the trail nginx at `/api`) routes **every** public API path to exactly one service.
External paths equal service paths (`/v1/...`); there is no prefix rewriting. This file is the contract:
`RouteTableIT` parses the table below, sends each example request through the real gateway to per-service WireMock
backends and checks the target service, the forwarded path, and the token rule (`public` reaches the service
without a token; `token` is 401 without one). `RouteDocsTest` checks that every pattern in
`in.dexter.gateway.domain.RouteTable` appears here and nothing else does. An unknown path is always
404 `NOT_FOUND` — with or without a token.

Columns: **Pattern** (Spring `PathPattern`; `*` inside a segment covers `:action` suffixes), **Example** (method and
a concrete path), **Service**, **Access** (`public` = no token needed at the edge; the service still applies its own
rules), **Class** (rate-limit class, MASTER_PLAN 7.3: `auth` 10/min, `write` 120/min, `read` 600/min, `calc`
60/min; per account when authenticated, else per client IP), **Phase** that ships the endpoints.

Route order matters only where prefixes overlap: `reco-stream` before `reco`, `commerce-affiliate-webhooks` before
`billing`. Routes marked **SSE** have no circuit breaker and no response timeout; every other route has a circuit
breaker (Resilience4j, per service) and a 10 s response timeout.

## Routes

| Pattern | Example | Service | Access | Class | Phase |
|---|---|---|---|---|---|
| `/v1/recommendations/stream` | `GET /v1/recommendations/stream?ticket=t1` | reco | public | read | P15 (**SSE**) |
| `/v1/webhooks/affiliate/**` | `POST /v1/webhooks/affiliate/cuelinks` | commerce | public | write | P17 |
| `/v1/auth/**` | `POST /v1/auth/login` | identity | public | auth | P04 |
| `/v1/auth/**` | `POST /v1/auth/register` | identity | public | auth | P04 |
| `/v1/auth/**` | `POST /v1/auth/verify-email` | identity | public | auth | P04 |
| `/v1/auth/**` | `POST /v1/auth/token/refresh` | identity | public | auth | P04 |
| `/v1/auth/**` | `POST /v1/auth/password/forgot` | identity | public | auth | P04 |
| `/v1/auth/**` | `POST /v1/auth/password/reset` | identity | public | auth | P04 |
| `/v1/auth/**` | `POST /v1/auth/mfa/verify` | identity | public | auth | P05 |
| `/v1/auth/**` | `POST /v1/auth/logout` | identity | token | auth | P04 |
| `/v1/auth/**` | `POST /v1/auth/step-up` | identity | token | auth | P05 |
| `/v1/me` | `GET /v1/me` | identity | token | read | P04 |
| `/v1/me/**` | `GET /v1/me/sessions` | identity | token | read | P04 |
| `/v1/me/**` | `POST /v1/me/mfa/totp/enroll` | identity | token | write | P05 |
| `/v1/admin/auth/**` | `POST /v1/admin/auth/login` | identity | public | auth | P05 |
| `/v1/admin/auth/**` | `POST /v1/admin/auth/mfa/setup` | identity | public | auth | P05 |
| `/v1/admin/auth/**` | `POST /v1/admin/auth/mfa/verify` | identity | public | auth | P05 |
| `/v1/admin/auth/**` | `POST /v1/admin/auth/token/refresh` | identity | public | auth | P05 |
| `/v1/admin/auth/**` | `POST /v1/admin/auth/invite/accept` | identity | public | auth | P05 |
| `/v1/admin/auth/**` | `POST /v1/admin/auth/logout` | identity | token | auth | P05 |
| `/v1/admin/me` | `GET /v1/admin/me` | identity | token | read | P05 |
| `/v1/admin/admins` | `GET /v1/admin/admins?limit=10` | identity | token | read | P05 |
| `/v1/admin/admins/**` | `PATCH /v1/admin/admins/0192a5a1-0000-7000-8000-0000000000d1` | identity | token | write | P05 |
| `/v1/admin/accounts` | `GET /v1/admin/accounts?email=asha@demo.in` | identity | token | read | P05 |
| `/v1/admin/accounts/**` | `GET /v1/admin/accounts/0192a5a1-0000-7000-8000-000000000001` | identity | token | read | P18 |
| `/v1/admin/elevations` | `POST /v1/admin/elevations` | identity | token | write | P05 |
| `/v1/admin/elevations/**` | `DELETE /v1/admin/elevations/current` | identity | token | write | P05 |
| `/.well-known/jwks.json` | `GET /.well-known/jwks.json` | identity | public | read | P04 |
| `/v1/expenses` | `GET /v1/expenses?limit=50` | ledger | token | read | P07 |
| `/v1/expenses/**` | `PATCH /v1/expenses/0192a5a1-0000-7000-8000-0000000000e1` | ledger | token | write | P07 |
| `/v1/expenses/**` | `POST /v1/expenses/0192a5a1-0000-7000-8000-0000000000e1:restore` | ledger | token | write | P07 |
| `/v1/expenses/**` | `GET /v1/expenses/search?q=swiggy` | ledger | token | read | P08 |
| `/v1/expenses:*` | `POST /v1/expenses:bulk` | ledger | token | write | P08 |
| `/v1/categories` | `GET /v1/categories` | ledger | token | read | P07 |
| `/v1/categories/**` | `PATCH /v1/categories/0192a5a1-0000-7000-8000-0000000000c1` | ledger | token | write | P07 |
| `/v1/merchants/**` | `GET /v1/merchants/autocomplete?q=swi` | ledger | token | read | P08 |
| `/v1/rules` | `POST /v1/rules` | ledger | token | write | P08 |
| `/v1/rules/**` | `DELETE /v1/rules/0192a5a1-0000-7000-8000-0000000000f1` | ledger | token | write | P08 |
| `/v1/imports` | `POST /v1/imports` | ledger | token | write | P08 |
| `/v1/imports/**` | `GET /v1/imports/0192a5a1-0000-7000-8000-0000000000f2` | ledger | token | read | P08 |
| `/v1/summary` | `GET /v1/summary?from=2026-09-01&to=2026-09-30&groupBy=category` | ledger | token | read | P08 |
| `/v1/calendar` | `GET /v1/calendar?month=2026-09` | ledger | token | read | P08 |
| `/v1/catalog/**` | `GET /v1/catalog/cards?limit=24` | catalog | public | read | P09 |
| `/v1/catalog/**` | `GET /v1/catalog/cards/mock-hdfc-regalia` | catalog | public | read | P09 |
| `/v1/admin/catalog/**` | `POST /v1/admin/catalog/cards/0192a5a1-0000-7000-8000-000000000a01:publish` | catalog | token | write | P09 |
| `/v1/admin/ingestion/**` | `GET /v1/admin/ingestion/sources` | catalog | token | read | P10 |
| `/v1/admin/review` | `GET /v1/admin/review` | catalog | token | read | P10 |
| `/v1/admin/review/**` | `POST /v1/admin/review/0192a5a1-0000-7000-8000-000000000a02:approve` | catalog | token | write | P10 |
| `/v1/cards` | `GET /v1/cards` | portfolio | token | read | P11 |
| `/v1/cards/**` | `GET /v1/cards/0192a5a1-0000-7000-8000-000000000b01/statements` | portfolio | token | read | P11 |
| `/v1/emi-plans` | `GET /v1/emi-plans` | portfolio | token | read | P11 |
| `/v1/emi-plans/**` | `PATCH /v1/emi-plans/0192a5a1-0000-7000-8000-000000000b02` | portfolio | token | write | P11 |
| `/v1/links/**` | `GET /v1/links/suggestions` | portfolio | token | read | P11 |
| `/v1/portfolio/**` | `GET /v1/portfolio/report?period=month` | portfolio | token | read | P11 |
| `/v1/rewards/**` | `GET /v1/rewards/balance` | portfolio | token | read | P11 |
| `/v1/statements/**` | `POST /v1/statements/uploads` | portfolio | token | write | P12 |
| `/v1/calc/**` | `POST /v1/calc/emi` | portfolio | token | calc | P12 |
| `/v1/budgets` | `GET /v1/budgets` | planning | token | read | P13 |
| `/v1/budgets/**` | `GET /v1/budgets/status` | planning | token | read | P13 |
| `/v1/liabilities` | `POST /v1/liabilities` | planning | token | write | P13 |
| `/v1/liabilities/**` | `GET /v1/liabilities/0192a5a1-0000-7000-8000-000000000c02/schedule` | planning | token | read | P13 |
| `/v1/scenarios` | `GET /v1/scenarios` | planning | token | read | P13 |
| `/v1/scenarios/**` | `POST /v1/scenarios/0192a5a1-0000-7000-8000-000000000c03:evaluate` | planning | token | write | P13 |
| `/v1/recommendations` | `GET /v1/recommendations` | reco | token | read | P14 |
| `/v1/recommendations/**` | `GET /v1/recommendations/best-card?category=dining&amountPaise=50000` | reco | token | read | P15 |
| `/v1/recommendations/**` | `POST /v1/recommendations/stream-ticket` | reco | token | write | P15 |
| `/v1/plans` | `GET /v1/plans` | billing | public | read | P16 |
| `/v1/plans/**` | `GET /v1/plans/premium` | billing | public | read | P16 |
| `/v1/subscriptions/**` | `POST /v1/subscriptions/me:cancel` | billing | token | write | P16 |
| `/v1/entitlements/**` | `GET /v1/entitlements/me` | billing | token | read | P16 |
| `/v1/invoices` | `GET /v1/invoices` | billing | token | read | P16 |
| `/v1/invoices/**` | `GET /v1/invoices/0192a5a1-0000-7000-8000-000000000d02` | billing | token | read | P16 |
| `/v1/webhooks/**` | `POST /v1/webhooks/razorpay` | billing | public | write | P16 |
| `/v1/deals` | `GET /v1/deals` | commerce | public | read | P17 |
| `/v1/deals/**` | `GET /v1/deals/0192a5a1-0000-7000-8000-000000000e02` | commerce | public | read | P17 |
| `/v1/card-applications` | `POST /v1/card-applications` | commerce | token | write | P17 |
| `/v1/card-applications/**` | `GET /v1/card-applications/0192a5a1-0000-7000-8000-000000000e03` | commerce | token | read | P17 |
| `/v1/ads/**` | `GET /v1/ads/serve?placement=home` | commerce | public | read | P17 |
| `/v1/notifications` | `GET /v1/notifications` | audit | token | read | P18 |
| `/v1/notifications/**` | `POST /v1/notifications/0192a5a1-0000-7000-8000-000000000f02:read` | audit | token | write | P18 |
| `/v1/notification-prefs` | `GET /v1/notification-prefs` | audit | token | read | P18 |
| `/v1/notification-prefs/**` | `PUT /v1/notification-prefs/email` | audit | token | write | P18 |
| `/v1/privacy/**` | `POST /v1/privacy/export` | audit | token | write | P18 |
| `/v1/admin/audit` | `GET /v1/admin/audit` | audit | token | read | P18 |
| `/v1/admin/audit/**` | `GET /v1/admin/audit/0192a5a1-0000-7000-8000-000000000f03` | audit | token | read | P18 |
| `/v1/admin/audit:*` | `POST /v1/admin/audit:verify-chain` | audit | token | write | P18 |
| `/v1/admin/activity/**` | `GET /v1/admin/activity/dashboard` | audit | token | read | P18 |

## Dev profile only

| Pattern | Example | Service | Access | Class | Phase |
|---|---|---|---|---|---|
| `/_dev/p04/**` (dev) | `POST /_dev/p04/keys/rotate` | identity | public | write | P04 |

## Not routed (on purpose)

- `/oauth2/token` (service client credentials) and `/internal/**`: service-to-service only, never at the edge.
- `/actuator/**` of the services: not routed; the gateway answers its own `/actuator/health` and `prometheus`.
- `/_dev/**` other than the dev-profile row above.

## Edge behaviour common to every route

| Filter (order) | Effect |
|---|---|
| 1. request-id | `X-Request-Id` kept if sane (`[A-Za-z0-9._:-]{1,128}`), else a UUIDv7; forwarded and echoed |
| 2. header hygiene | `X-Account-*`, `X-Actor-*`, `X-User-*`, roles/permissions/tenant-style headers removed; client IP from `X-Forwarded-For` behind trusted proxies only |
| 3. body size | > 256 KB → 413 `PAYLOAD_TOO_LARGE` (declared or streamed) |
| 4. JWT pre-check | non-public route without a valid user/admin token → 401 `UNAUTHENTICATED` (`WWW-Authenticate`); service tokens → 403; JWKS down → 503 |
| 5. rate limit | `RateLimit-Policy: "<class>";q=<limit>;w=60`, `RateLimit: "<class>";r=<remaining>;t=<s>`; 429 `RATE_LIMITED` + `Retry-After`; Redis down → `auth` 503, others pass |
| 6. circuit breaker | per service (not SSE); open → 503 `UPSTREAM_UNAVAILABLE`; slow → 504 `UPSTREAM_TIMEOUT` |
| 7. security headers | `X-Content-Type-Options`, `Referrer-Policy`, `X-Frame-Options`, `Content-Security-Policy: default-src 'none'…`, HSTS (not in dev) |
| 8. CORS | allowlist per profile (dev: `http://localhost:8099`, `http://127.0.0.1:8099`; prod: none until configured) |
| activity | sampled `gateway.activity.recorded` v1 to `dexter.gateway.activity.v1`, asynchronous, dropped rather than ever blocking |
