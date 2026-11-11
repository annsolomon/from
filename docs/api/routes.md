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
| `/v1/expenses/**` | `GET /v1/expenses/daily-totals?from=2026-10-01&to=2026-10-31` | ledger | token | read | P07 |
| `/v1/expenses/**` | `PATCH /v1/expenses/0192a5a1-0000-7000-8000-0000000000e1` | ledger | token | write | P07 |
| `/v1/expenses/**` | `POST /v1/expenses/0192a5a1-0000-7000-8000-0000000000e1:restore` | ledger | token | write | P07 |
| `/v1/expenses/**` | `GET /v1/expenses/search?q=swiggy` | ledger | token | read | P08 |
| `/v1/expenses:*` | `POST /v1/expenses:bulk` | ledger | token | write | P08 |
| `/v1/categories` | `GET /v1/categories` | ledger | token | read | P07 |
| `/v1/categories/**` | `PATCH /v1/categories/0192a5a1-0000-7000-8000-0000000000c1` | ledger | token | write | P07 |
| `/v1/merchants/**` | `GET /v1/merchants/autocomplete?q=swi` | ledger | token | read | P08 |
| `/v1/merchants/**` | `POST /v1/merchants/resolve` | ledger | token | write | P08 |
| `/v1/rules` | `POST /v1/rules` | ledger | token | write | P08 |
| `/v1/rules/**` | `DELETE /v1/rules/0192a5a1-0000-7000-8000-0000000000f1` | ledger | token | write | P08 |
| `/v1/imports` | `POST /v1/imports` | ledger | token | write | P08 |
| `/v1/imports/**` | `GET /v1/imports/0192a5a1-0000-7000-8000-0000000000f2` | ledger | token | read | P08 |
| `/v1/imports/**` | `POST /v1/imports/0192a5a1-0000-7000-8000-0000000000f2:start` | ledger | token | write | P08 |
| `/v1/summary` | `GET /v1/summary?from=2026-09-01&to=2026-09-30&groupBy=category` | ledger | token | read | P08 |
| `/v1/calendar` | `GET /v1/calendar?month=2026-09` | ledger | token | read | P08 |
| `/v1/catalog/**` | `GET /v1/catalog/cards?limit=24` | catalog | public | read | P09 |
| `/v1/catalog/**` | `GET /v1/catalog/cards/mock-hdfc-regalia` | catalog | public | read | P09 |
| `/v1/catalog/**` | `GET /v1/catalog/cards/search?q=regalia` | catalog | public | read | P09 |
| `/v1/catalog/**` | `GET /v1/catalog/categories/potential?category=food.delivery` | catalog | public | read | P09 |
| `/v1/catalog/**` | `GET /v1/catalog/offers?category=food.delivery` | catalog | public | read | P09 |
| `/v1/admin/catalog/**` | `POST /v1/admin/catalog/cards/0192a5a1-0000-7000-8000-000000000a01:publish` | catalog | token | write | P09 |
| `/v1/admin/catalog/**` | `PUT /v1/admin/catalog/cards/0192a5a1-0000-7000-8000-000000000a01/draft` | catalog | token | write | P09 |
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
| `/v1/calc/**` | `POST /v1/calc/exit-paths` | portfolio | token | calc | P12 |
| `/v1/calc/**` | `POST /v1/calc/scenarios/minimum-payment` | portfolio | token | calc | P12 |
| `/v1/budgets` | `GET /v1/budgets` | planning | token | read | P13 |
| `/v1/budgets/**` | `GET /v1/budgets/status` | planning | token | read | P13 |
| `/v1/liabilities` | `POST /v1/liabilities` | planning | token | write | P13 |
| `/v1/liabilities/**` | `GET /v1/liabilities/0192a5a1-0000-7000-8000-000000000c02/schedule` | planning | token | read | P13 |
| `/v1/scenarios` | `GET /v1/scenarios` | planning | token | read | P13 |
| `/v1/scenarios/**` | `POST /v1/scenarios/0192a5a1-0000-7000-8000-000000000c03:evaluate` | planning | token | write | P13 |
| `/v1/recommendations` | `GET /v1/recommendations` | reco | token | read | P14 |
| `/v1/recommendations/**` | `GET /v1/recommendations/wallet` | reco | token | read | P15 |
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
| `/v1/admin/commerce/**` | `POST /v1/admin/commerce/card-applications/0192a5a1-0000-7000-8000-000000000e04:decide` | commerce | token | write | P17 |
| `/r/**` | `GET /r/AQFkAAAAAAAAAAAAAAAAAAAAAA` | commerce | public | read | P17 |
| `/v1/notifications` | `GET /v1/notifications` | audit | token | read | P18 |
| `/v1/notifications/**` | `POST /v1/notifications/0192a5a1-0000-7000-8000-000000000f02:read` | audit | token | write | P18 |
| `/v1/notification-prefs` | `GET /v1/notification-prefs` | audit | token | read | P18 |
| `/v1/notification-prefs/**` | `PUT /v1/notification-prefs/email` | audit | token | write | P18 |
| `/v1/privacy/**` | `POST /v1/privacy/export` | audit | token | write | P18 |
| `/v1/admin/audit` | `GET /v1/admin/audit` | audit | token | read | P18 |
| `/v1/admin/audit/**` | `GET /v1/admin/audit/privacy-requests/0192a5a1-0000-7000-8000-000000000f03` | audit | token | read | P18 |
| `/v1/admin/audit:*` | `POST /v1/admin/audit:verify-chain` | audit | token | write | P18 |
| `/v1/admin/activity/**` | `GET /v1/admin/activity/dashboard` | audit | token | read | P18 |

## Dev profile only

| Pattern | Example | Service | Access | Class | Phase |
|---|---|---|---|---|---|
| `/_dev/p04/**` (dev) | `POST /_dev/p04/keys/rotate` | identity | public | write | P04 |

## `/v1/calc/*` (P12b, portfolio)

Stateless, POST only, no database, no idempotency key, no ETag: every answer is a pure function of the body
(ADR-0018). Money is paise and rates are basis points throughout. A body is at most 64 KB (the service's own cap,
inside the gateway's 256 KB), and each endpoint bounds its lists and cycle counts — `CalcService` holds the numbers.

| Path | Engine |
|---|---|
| `POST /v1/calc/emi` | EMI quote and schedule (P11's `EmiMath`, the engine `emi_plans` uses) |
| `POST /v1/calc/ledger` | one cycle's interest, lot by lot, and its closing lots |
| `POST /v1/calc/minimum-due` | the four minimum-due formulas |
| `POST /v1/calc/late-fee` | the late-fee tier table plus RBI SMA/NPA staging |
| `POST /v1/calc/utilization` | spend against the limit, and the over-limit fee |
| `POST /v1/calc/custom-fees` | configured per-card fees for one cycle, with GST |
| `POST /v1/calc/scenarios/full-payment` | pay the statement in full |
| `POST /v1/calc/scenarios/partial-payment` | pay part of it: interest, carried principal, grace lost |
| `POST /v1/calc/scenarios/no-payment` | ignore it: fees and staging at 30/60/90 days |
| `POST /v1/calc/scenarios/minimum-payment` | minimum-only, cycle by cycle, to payoff or to divergence |
| `POST /v1/calc/repayment-path` | avalanche against snowball across cards |
| `POST /v1/calc/exit-paths` | pay in full, a targeted payoff, or minimum-only |

## `/v1/budgets`, `/v1/liabilities`, `/v1/scenarios` (P13, planning)

Money is paise and rates are basis points, as everywhere. Days are the account's local days (Asia/Kolkata): the
ledger stamps every expense with its IST day and planning puts it in the period that day falls in (ADR-0019).

| Path | What it does |
|---|---|
| `POST /v1/budgets` | create a budget (Idempotency-Key): a period (weekly/monthly/custom), an optional category, a limit and its alert thresholds |
| `GET /v1/budgets` | the account's budgets, newest first (keyset) |
| `GET /v1/budgets/status` | every active budget with its current period: spent, remaining, used in bps, the run-rate projection and the thresholds reached |
| `GET /v1/budgets/{id}` | one budget (ETag) |
| `GET /v1/budgets/{id}/history` | that budget's periods, newest first (keyset) |
| `PATCH /v1/budgets/{id}` | If-Match: name, limit, rollover, thresholds, endsOn, status |
| `DELETE /v1/budgets/{id}` | end it; its periods and alerts stay |
| `POST /v1/liabilities` | record a liability (Idempotency-Key): the instalment and the whole schedule are computed once by the shared EMI engine and stored |
| `GET /v1/liabilities` / `GET /v1/liabilities/{id}` | the account's liabilities (keyset) and one of them (ETag) |
| `GET /v1/liabilities/{id}/schedule` | its instalments in order (keyset) |
| `PATCH /v1/liabilities/{id}` | If-Match: lender, status |
| `DELETE /v1/liabilities/{id}` | close it; the schedule stays as recorded |
| `POST /v1/scenarios` | save a what-if (Idempotency-Key): the account's active budgets and liabilities are copied in |
| `GET /v1/scenarios` / `GET /v1/scenarios/{id}` | the account's scenarios (keyset) and one of them (ETag) |
| `PATCH /v1/scenarios/{id}` | If-Match: name, adjustments. The snapshot is never re-taken |
| `POST /v1/scenarios/{id}:evaluate` | apply the adjustments to the snapshot; stores nothing, so no key |

`POST /v1/calc/emi` is **portfolio's** (A-42): one owner per path, and the EMI engine is a shared library
(`libs/portfolio-calc`, A-74) that planning calls directly rather than over HTTP.

## `/v1/recommendations` (P14, reco)

Reads only — a recommendation is never submitted. The recompute is event-driven (a sweeper claims an account once
its events have gone quiet) or happens inside the read when the stored result's state hash no longer matches the
inputs, so a read is always current (ADR-0020). Money is paise, scores are basis points of 1.

| Path | What it does |
|---|---|
| `GET /v1/recommendations` | every published card scored against the account's spend profile: `recommended` sorted by the blended score, plus the `notRecommended` bucket for a card below the threshold or one that costs more than it earns. A card the account holds is marked `held`, not dropped |
| `GET /v1/recommendations?cardProductIds=a,b` | score only those products (at most 50), for ranking a search result. Never cached: a different question from "what should I get" |
| `GET /v1/recommendations/explain/{cardProductId}` | the arithmetic: one line per category root (the rule that earned, the share of the root it was credited with, the spend considered, what it earned, whether a cap bound it), the fee after its waiver probability, the milestone, the reward rate per root and the top reasons |

The blend is **60 % rupees saved, 40 % expense fit**, configurable (`dexter.reco.scoring.savings-weight-bps`), and
every response reports the weight and threshold it used. Reco holds no spend of its own: the profile comes from
`ledger.expense.*`, the held cards from `portfolio.card.*` and the card terms from the compacted catalog topic.

## `/v1/recommendations/wallet`, `/best-card` and `/stream` (P15, reco)

Three different questions, so three shapes. None is cached: the wallet because it is cheap and asked for
deliberately, best-card-now because the answer depends on the amount, the stream because it is a notification
(ADR-0021 §5).

| Path | What it does |
|---|---|
| `GET /v1/recommendations/wallet` | the best one-, two- and three-card wallet. Each category root is routed to exactly one card, caps are counted per card, and fees and milestones follow the spend actually routed to each card — so the answer is not the top three of `/v1/recommendations`. Every size reports its `marginalValuePaise` over the size below, which may be negative, and `exact: false` when too many roots were contested to route them exhaustively |
| `GET /v1/recommendations/wallet?size=n` | the same, up to `n` cards (1 to 3) |
| `GET /v1/recommendations/best-card?merchantId=&category=&amountPaise=` | which **held** card to put this purchase on. Give `merchantId` or `category` (or both) and a positive `amountPaise`. Each card reports its reward, any active offers, a milestone this purchase would reach, the cap headroom left this period and whether that cap is already used |
| `POST /v1/recommendations/stream-ticket` | a one-time ticket, 60 seconds, for opening the stream from a browser |
| `GET /v1/recommendations/stream?ticket=` | Server-Sent Events: the current result at once, then one `recommendation.updated` per recompute, with the state hash as the event id, and a `heartbeat` every 25 s. Public at the edge because the ticket is the credential — an `EventSource` cannot send an `Authorization` header. `Last-Event-ID` is honoured: a reconnect that already has the latest result is told `recommendation.unchanged`. It produces `text/event-stream` only, so a client that does not accept it gets 406 before the ticket is read; one that does gets 401 `STREAM_TICKET_INVALID` for a spent or unknown ticket (ADR-0021 §15) |

A push reaches a viewer whatever instance recomputed, because a recompute publishes on the Redis channel
`reco:acct:{id}` and every instance relays it to the streams it holds (A-87). Delivery is best-effort by design:
the stored result is the system of record, and a missed push is corrected by the next one, by the heartbeat
failing, or by the reconnect replay.

## `/v1/plans`, `/v1/subscriptions`, `/v1/entitlements`, `/v1/invoices`, `/v1/webhooks` (P16, billing)

Prices are paise before GST; a checkout charges the price plus 18 % (ADR-0022 §6). The checkout and the cancel are
billing changes, so both need a recent sign-in (step-up, MASTER_PLAN 7.1).

| Path | What it does |
|---|---|
| `GET /v1/plans` / `GET /v1/plans/{code}` | public: the active plans with their price, GST, total and feature map (three rows, not paged, A-93) |
| `POST /v1/subscriptions/checkout` | Idempotency-Key **required**, step-up, **Mode B**: `{plan, paymentMethod: pm_..., billingState}` → 201 succeeded, 202 pending (the provider's webhook decides it), 402 failed. 409 `SUBSCRIPTION_EXISTS` while another subscription is live |
| `GET /v1/subscriptions/me` | the live subscription, else the newest one (ETag); 404 for an account that never had one |
| `POST /v1/subscriptions/me:cancel` | step-up, optional Idempotency-Key: cancel at the end of the period; the plan's entitlements last until then. Repeating it changes nothing |
| `GET /v1/entitlements/me` | the tier and the effective feature map (plan + unexpired overrides), cached 60 s per account; the tier is also the `tier` claim of the next token identity issues |
| `GET /v1/invoices` / `GET /v1/invoices/{id}` | the account's invoices, newest first (keyset), each with its lines: the item and CGST + SGST or IGST |
| `POST /v1/webhooks/{provider}` | public; the provider's HMAC signature (`Webhook-Signature: t=..,v1=..`, ±5 min) is the credential. 200 `processed` / `duplicate` / `ignored`; 400 for a bad signature or payload, 404 for an unknown provider |

## `/v1/deals`, `/r/{token}`, `/v1/card-applications`, `/v1/ads`, `/v1/webhooks/affiliate`, `/v1/admin/commerce` (P17, commerce)

The feed, a deal, the ad slot and the redirect are public; a user token, when sent, must verify (else 401) and then
personalises card-match and ad-free. Money is paise, rates basis points (ADR-0023).

| Path | What it does |
|---|---|
| `GET /v1/deals?category=&limit=&cursor=` | the ranked marketplace: `score = 0.8·(0.6·cardMatch + 0.4·savingsNorm) + 0.2·sponsor` over the bounded active set, keyset-paged on (score, priority, id). Each deal has its saving at its reference spend, `cardMatch` `held` / `open` / `other` (a ranking branch, never a filter) and a signed `clickPath` |
| `GET /v1/deals/{id}?spendPaise=` | one live deal with each component's saving at that spend (default: the reference spend); every component is capped on its own (₹75,000 at 10 % cap ₹5,000 + 5 % cap ₹2,000 = ₹7,000) |
| `GET /r/{token}` | the signed click redirect: 302 to the merchant with `subid=<click id>` (or to the ad's URL), `Cache-Control: no-store`, `Referrer-Policy: no-referrer`. 400 `CLICK_TOKEN_INVALID` for an altered token, 410 `CLICK_TOKEN_EXPIRED` after 7 days, 410 `DEAL_UNAVAILABLE` once the deal ended |
| `POST /v1/card-applications` | user token, Idempotency-Key **required**: `{cardProductId}` → 201 submitted. 400 `UNKNOWN_CARD_PRODUCT` (field error) for a card the catalog has not published, 409 `APPLICATION_EXISTS` while one is open for the same card |
| `GET /v1/card-applications` / `GET /v1/card-applications/{id}` | the account's applications, newest first (keyset), each with its status history |
| `POST /v1/card-applications/{id}:withdraw` | withdraw an open application (409 `APPLICATION_NOT_OPEN` once decided) |
| `GET /v1/ads/serve?placement=` | one ad or none: `{placement, ad, reason}` with reason `AD_FREE` (billing's `adFree` entitlement), `NO_FILL` or `FREQUENCY_CAPPED`. Weighted rotation; per-visitor frequency caps |
| `POST /v1/webhooks/affiliate/{network}` | public; `Postback-Signature: t=..,v1=..` (±5 min) is the credential. `{ref, subid, status, amountPaise, commissionPaise, occurredAt}` → 200 `created` / `updated` / `duplicate` (idempotent by network + ref). 404 `NETWORK_UNKNOWN`, 400 `POSTBACK_SIGNATURE_INVALID` |
| `POST /v1/admin/commerce/deals` / `GET` / `GET /{id}` / `POST /{id}:status` (If-Match) | `commerce:write` / `commerce:read`: deals and their status (draft → active ⇄ paused → ended) |
| `GET /v1/admin/commerce/card-applications` / `GET /{id}` / `POST /{id}:decide` (If-Match) | `commerce:read` / `commerce:decide`: the open queue, oldest first; `{decision: in_review \| approved \| rejected, reason}` (a rejection needs a reason) |
| `POST /v1/admin/commerce/campaigns` / `GET` / `GET /{id}` / `PUT /{id}` (If-Match) / `DELETE /{id}` / `POST /{id}/creatives` / `PUT /{id}/creatives/{cid}` (If-Match) | `commerce:write` / `commerce:read`: campaign CRUD (DELETE ends it) and its creatives |
| `GET /v1/admin/commerce/stats?from=&to=` | `commerce:read`: per deal clicks, daily uniques, conversions and approved commission; per campaign impressions and clicks — from the maintained counters, IST days, at most 92 |

## `/v1/notifications`, `/v1/notification-prefs`, `/v1/privacy`, `/v1/admin/audit`, `/v1/admin/activity` (P18, audit)

User endpoints take a user token (audience `audit`); admin endpoints an admin token with `audit:read` (SUPPORT,
SUPER_ADMIN) or `audit:verify` (SUPER_ADMIN). ADR-0024.

| Path | What it does |
|---|---|
| `GET /v1/notifications?unread=&limit=&cursor=` | the in-app inbox, newest first (keyset), each with its rendered `title` / `body`, plus `unread` (the count of unread in-app notifications). Notifications come from identity's security alerts, planning's budget thresholds, billing's subscription changes, commerce's application decisions and a ready export |
| `POST /v1/notifications/{id}:read` | marks one in-app notification read (404 `NOTIFICATION_NOT_FOUND` for someone else's) |
| `GET /v1/notification-prefs` / `PATCH` (If-Match, `"0"` before the first save) | `{email, push, muted}`: channel switches and muted categories (`budget`, `billing`, `commerce`, `privacy`). Security cannot be muted (400 `NOT_MUTABLE`) and always goes in-app and by email |
| `POST /v1/privacy/export` / `POST /v1/privacy/delete` | step-up (a sign-in within 10 minutes), Idempotency-Key **required**: 202 with the request. 409 `PRIVACY_REQUEST_RUNNING` while one of the kind runs. An export ends as one zip (every service's JSON part and audit's own); a deletion freezes the account, erases or anonymizes it in every service, shreds its data key and leaves only a pseudonymous record |
| `GET /v1/privacy/requests` / `GET /v1/privacy/requests/{id}` | the account's requests: phase, attempt, every service's answer per phase, and for a completed export `downloadUrl` (presigned, valid until `downloadExpiresAt`, 24 h after completion). A completed deletion no longer belongs to the account |
| `GET /v1/admin/audit?actorId=&action=&targetType=&targetId=&from=&to=&limit=&cursor=` | `audit:read`: the hash-chained log, newest first (keyset on `seq`), each entry with its `hash` |
| `POST /v1/admin/audit:verify-chain` | `audit:read`: walks the chain to the head — every row's link, every row's hash recomputed from its columns, every anchor against its row and its exported copy — and answers `{ok, checked, headSeq, brokenAt, reason, anchorsChecked}`. The run is itself audited |
| `GET /v1/admin/audit/privacy-requests/{id}` / `POST /{id}:resume` | `audit:read` / `audit:verify`: a request's full state (incl. a pseudonymized one); resume a `failed` request at its phase, asking only the services that have not answered (409 `PRIVACY_REQUEST_NOT_FAILED`) |
| `GET /v1/admin/activity/dashboard?hours=` | `audit:read`: requests, 4xx, 5xx, average and max latency per hour and per service over the last `hours` (1..336), from the hourly aggregate of the gateway's sampled activity |

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
