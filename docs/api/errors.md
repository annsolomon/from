# Error code catalogue

ProblemDetail (RFC 9457) with a stable `code` (MASTER_PLAN 9). Body members: `type`, `title`, `status`, `detail`, `instance`, `code`, `requestId`, and `errors[]` (`field`, `code`, `message`) for field problems. `type` = `https://docs.dexter.in/problems/<code-in-kebab-case>`.

## Shared codes (dexter-core `CoreErrors`, P01)

| Code | HTTP | Meaning |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Request validation failed |
| `MALFORMED_JSON` | 400 | Malformed JSON body |
| `UNKNOWN_FIELD` | 400 | Unknown JSON field |
| `DUPLICATE_FIELD` | 400 | Duplicate JSON field |
| `BAD_REQUEST` | 400 | Bad request |
| `INVALID_ID` | 400 | Invalid identifier |
| `LIMIT_INVALID` | 400 | Invalid page limit |
| `CURSOR_INVALID` | 400 | Invalid cursor |
| `CURSOR_FILTER_MISMATCH` | 400 | Cursor does not match the filters |
| `IF_MATCH_INVALID` | 400 | Invalid If-Match header |
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | Idempotency-Key header required |
| `UNAUTHENTICATED` | 401 | Authentication required |
| `FORBIDDEN` | 403 | Forbidden |
| `NOT_FOUND` | 404 | Not found |
| `METHOD_NOT_ALLOWED` | 405 | Method not allowed |
| `NOT_ACCEPTABLE` | 406 | Not acceptable |
| `CONFLICT` | 409 | Conflict |
| `IDEMPOTENCY_IN_PROGRESS` | 409 | Request with this Idempotency-Key is in progress (P02: sent with `Retry-After: 1` and `retryAfterSeconds`) |
| `VERSION_CONFLICT` | 412 | Resource version conflict |
| `PAYLOAD_TOO_LARGE` | 413 | Payload too large |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | Unsupported media type |
| `IDEMPOTENCY_KEY_REUSED` | 422 | Idempotency-Key reused with a different request |
| `MONEY_OVERFLOW` | 422 | Amount out of range |
| `IF_MATCH_REQUIRED` | 428 | If-Match header required |
| `RATE_LIMITED` | 429 | Too many requests |
| `INTERNAL_ERROR` | 500 | Internal error |
| `SERVICE_UNAVAILABLE` | 503 | Service unavailable |

Spring MVC exceptions are mapped by `ProblemDetailsAdvice`: unknown JSON field → `UNKNOWN_FIELD`, duplicate key → `DUPLICATE_FIELD`, unreadable body or null for a primitive → `MALFORMED_JSON`, bean validation / missing or mistyped parameter → `VALIDATION_FAILED`, no route → `NOT_FOUND`, wrong method → `METHOD_NOT_ALLOWED`, wrong content type → `UNSUPPORTED_MEDIA_TYPE`. Anything unexpected → `INTERNAL_ERROR` with no internals (the `requestId` finds the log line).

## Service codes

Each service appends its own table here in its phase.

### identity (P04, `IdentityErrors`)

| Code | HTTP | Meaning |
|---|---|---|
| `INVALID_CREDENTIALS` | 401 | Unknown email **or** wrong password — deliberately the same status, body and timing (one Argon2 verification either way, a dummy hash for unknown emails). |
| `EMAIL_NOT_VERIFIED` | 403 | Right password, email not verified yet. Only reachable with the correct password. |
| `ACCOUNT_UNAVAILABLE` | 403 | Right password, but the account is locked, disabled or being deleted. |
| `INVALID_REFRESH_TOKEN` | 401 | Refresh token unknown, expired (30-day idle / 90-day absolute), revoked, or its session was signed out. Sign in again. |
| `REFRESH_TOKEN_REUSED` | 401 | An already-rotated refresh token was presented: the whole family (session) was revoked and `identity.security.alert` raised (MASTER_PLAN 7.1). |
| `INVALID_TOKEN` | 400 | Email-verification or password-reset token unknown, already used, superseded or expired. |
| `CURRENT_PASSWORD_INVALID` | 403 | Password change with a wrong current password. |
| `UNKNOWN_CLIENT` | 400 | `clientId` is not one of the public clients (`dexter-app`, `dexter-web`, `dexter-trail`). |
| `INVALID_CLIENT` | 401 | `/oauth2/token`: missing, malformed or wrong HTTP Basic client credentials, or a disabled client. |
| `UNSUPPORTED_GRANT_TYPE` | 400 | `/oauth2/token`: only `client_credentials`. |
| `INVALID_AUDIENCE` | 400 | `/oauth2/token`: the client may not request a token for that `audience`. |
| `PHONE_TAKEN` | 409 | `PATCH /v1/me`: the phone number belongs to another account (unique keyed hash). |
| `MFA_CHALLENGE_INVALID` | 401 | P05: the `mfaToken` of a sign-in is unknown, expired (5 min), already used, or burnt after 5 wrong codes — sign in again. |
| `INVALID_MFA_CODE` | 401 | P05: wrong TOTP code, a code whose 30-second step was already used (replay), or an unknown/used recovery code. |
| `MFA_ALREADY_ENROLLED` | 409 | P05: enrol/setup while a confirmed authenticator exists. |
| `MFA_NOT_ENROLLED` | 409 | P05: confirm/disable/regenerate without an enrolment. |
| `ADMIN_MFA_REQUIRED` | 403 | P05: an admin-console token without `amr=mfa` (cannot happen with identity-issued tokens; defense in depth). |
| `NOT_BREAK_GLASS_ELIGIBLE` | 403 | P05: `POST /v1/admin/elevations` by an admin that is not break-glass eligible (also refused by RLS). |
| `ADMIN_EMAIL_TAKEN` | 409 | P05: `POST /v1/admin/admins` with an email that already has an admin. |
| `SELF_CHANGE_FORBIDDEN` | 403 | P05: `PATCH /v1/admin/admins/{own id}` — another SUPER_ADMIN must do it. |

Shared codes identity uses: `VALIDATION_FAILED` with `errors[].code` = `INVALID_EMAIL`, `REQUIRED`, `TOO_SHORT`,
`TOO_LONG`, `BREACHED`, `CONTAINS_EMAIL` (passwords), `INVALID_TZ`, `INVALID_LENGTH`, `INVALID_CHARACTERS`,
`INVALID_PHONE`; `UNKNOWN_FIELD` for a merge-patch member that cannot be changed; `IF_MATCH_REQUIRED` /
`IF_MATCH_INVALID` / `VERSION_CONFLICT` on `PATCH /v1/me`; `IDEMPOTENCY_*` on registration; `STEP_UP_REQUIRED` on
`logout-all` and `password/change`; `SERVICE_UNAVAILABLE` + `Retry-After: 1` when the Argon2 bulkhead is full.
Registration and forgot-password never fail on the email's existence: always `202 {"status":"accepted"}`.

P05 additions: `RATE_LIMITED` (429 + `Retry-After`) from the login/MFA/step-up throttles — per email hash (also for
unknown emails), per account/admin and per IP; progressive delay after 3 failures, 15-minute lock after 10.
`SERVICE_UNAVAILABLE` (503 + `Retry-After: 2`) when Redis is unreachable: sign-in fails closed. `FORBIDDEN` with
`missingPermissions` from `@RequiresPermission`; `FORBIDDEN` "Admin console token required" for user tokens on admin
endpoints and the reverse; `FORBIDDEN` "Break-glass elevation required" when a SUPER_ADMIN token's elevation has
ended or expired. `STEP_UP_REQUIRED` on MFA enrol/disable/recovery-codes (disable and regenerate also need `amr=mfa`).
`CONFLICT` when a break-glass elevation is already active; `VERSION_CONFLICT` (412) on stale admin `If-Match`.


## Retry-After (P02)

A `DexterException` carrying the property `retryAfterSeconds` (a positive number) is answered with a
`Retry-After` header of that many seconds, in addition to the ProblemDetail member. Used by
`IDEMPOTENCY_IN_PROGRESS` now, and by rate limits (P06) later.

## Security codes (dexter-security `SecurityErrors`, P02b)

| Code | HTTP | Meaning |
|---|---|---|
| `STEP_UP_REQUIRED` | 401 | A `@RequiresStepUp` action needs a login within the last 10 min (and `amr` containing `mfa` when MFA is enrolled). Members `maxAgeSeconds`, `mfaRequired`. Header `WWW-Authenticate: Bearer realm="dexter", error="insufficient_user_authentication", error_description="…", max_age=600` (RFC 9470). Clients re-authenticate; refreshing does not help. |

How dexter-security uses the shared codes:

| Situation | Code | HTTP | Header |
|---|---|---|---|
| No `Authorization: Bearer …` on a protected handler | `UNAUTHENTICATED` | 401 | `WWW-Authenticate: Bearer realm="dexter"` |
| Token rejected for any reason (signature, kid, alg, typ, iss, aud, exp, claims) | `UNAUTHENTICATED` | 401 | `WWW-Authenticate: Bearer realm="dexter", error="invalid_token"`. The reason is logged (`AUTH_REJECTED reason=…`), never returned. |
| Identity's JWKS unreachable and cached keys older than `jwks-max-stale` | `SERVICE_UNAVAILABLE` | 503 | `Retry-After: 5` |
| Service token on a user endpoint, user token on an `@InternalEndpoint`, service not in `callers` | `FORBIDDEN` | 403 | — |
| `@RequiresPermission` not satisfied | `FORBIDDEN` | 403 | — (member `missingPermissions`) |

## Cache (dexter-cache, P02b)

`dexter-cache` adds **no error codes** and never changes a response: a Redis failure on a read means *bypass to the
database* (logged once per breaker window as `CACHE_BYPASS`, counted as `cache_requests_total{result="bypass"}`).
A failed after-commit bump is logged as `CACHE_INVALIDATION_FAILED` and counted; Path B consumers throw
`CacheUnavailableException` so the event is retried (1 min, 10 min, DLQ) — never surfaced to a client.
The demo's `GET /demo/notes/{id}` returns the shared `NOT_FOUND` (404) for a missing or foreign note; that 404 is
cached for 30 s per account (negative caching).

## P02 dev lab (`/_dev/p02/*`, dev profile only)

Uses only shared codes. Lab-specific behaviour: a rejected token answers `401 UNAUTHENTICATED` **with** a `reason`
property (the verifier's `TokenRejectedException.Reason`) — the real interceptor never reveals it; the lab does because
it is a playground. `503 SERVICE_UNAVAILABLE` when the lab cannot start (Postgres `lab`, Kafka or Redis unreachable) or
when a cache bump/purge cannot reach Redis. Validation codes in `errors[]`: `UNKNOWN_PERSONA`, `UNKNOWN_DEFECT`,
`TITLE_INVALID`, `AMOUNT_INVALID`, `HOLD_INVALID`.

### gateway (P06, `GatewayErrors` + core codes)

| Code | Status | When |
|---|---|---|
| `NOT_FOUND` | 404 | No route for the path (`docs/api/routes.md`) — always 404, with or without a token. |
| `UNAUTHENTICATED` | 401 | Token route without a Bearer token, or a token that fails edge verification (`WWW-Authenticate: Bearer realm="dexter"[, error="invalid_token"]`). |
| `FORBIDDEN` | 403 | A service (client-credentials) token at the edge. |
| `PAYLOAD_TOO_LARGE` | 413 | Body over 256 KB (declared `Content-Length` or streamed). |
| `RATE_LIMITED` | 429 | Route-class bucket empty; `Retry-After` + `retryAfterSeconds`; `RateLimit-Policy` / `RateLimit` on every limited response. |
| `SERVICE_UNAVAILABLE` | 503 | JWKS unreachable (`Retry-After: 5`), or Redis down on an `auth`-class route (`Retry-After: 2`). |
| `UPSTREAM_UNAVAILABLE` | 503 | The service refused/reset the connection or its circuit breaker is open (`Retry-After: 5`). |
| `UPSTREAM_TIMEOUT` | 504 | No answer within the route's response timeout (10 s; SSE routes have none). |

Errors a service returns pass through the gateway unchanged (same body, same `X-Request-Id`).

### ledger (P07, `LedgerErrors` + core codes)

| Code | Status | When |
|---|---|---|
| `CLIENT_REF_TAKEN` | 409 | `clientRef` already used by another expense of the account (import de-duplication, unique index `expenses_client_ref`). |
| `CATEGORY_EXISTS` | 409 | `POST /v1/categories`: the derived path (`parentPath.slug(name)`) is already visible (system or the account's own). |
| `USER_TOKEN_REQUIRED` | 403 | An admin-console token (`scope=admin`) on an end-user ledger endpoint. |
| `VALIDATION_FAILED` | 400 | Field errors: `amountPaise` POSITIVE, `occurredAt` REQUIRED, `categoryPath`/`category` INVALID, `paymentMethod` REQUIRED/INVALID, `userCardId` NOT_A_CARD_PAYMENT, `tags` INVALID/TOO_MANY, `minPaise`/`maxPaise` INVALID/BELOW_MIN, `to` BEFORE_FROM, daily totals `to` INVALID_RANGE (> 366 days), `parentPath` REQUIRED/INVALID/UNKNOWN_CATEGORY/TOO_DEEP/NOT_UNDER_SYSTEM_ROOT, `name`/`icon` INVALID. |
| `UNKNOWN_FIELD` | 400 | PATCH body names a field that cannot be changed (only `occurredAt`, `amountPaise`, `merchant`, `categoryPath`, `paymentMethod`, `userCardId`, `note`, `tags`). |
| `IF_MATCH_REQUIRED` | 428 | PATCH without `If-Match`. |
| `VERSION_CONFLICT` | 412 | PATCH `If-Match` is not the current version (re-GET, re-apply). |
| `NOT_FOUND` | 404 | Unknown id, a deleted expense (GET/PATCH), another account's expense (RLS: never 403), unknown `:action`. |
| `INVALID_ID` | 400 | `POST /v1/expenses/{id}:restore` with a malformed id. |
| `IDEMPOTENCY_KEY_REQUIRED` / `IDEMPOTENCY_KEY_REUSED` / `IDEMPOTENCY_IN_PROGRESS` | 400 / 422 / 409 | `POST /v1/expenses` (key required); `POST /v1/categories` (key optional). |
| `CURSOR_INVALID` / `CURSOR_FILTER_MISMATCH` / `LIMIT_INVALID` | 400 | `GET /v1/expenses` keyset paging (MASTER_PLAN 4.5). |

`DELETE /v1/expenses/{id}` is idempotent: deleting a deleted expense is 204 again; `:restore` of a live expense
returns it unchanged.

### ledger P08 (merchants, search, bulk, imports, insights, rules)

| Code | Status | When |
|---|---|---|
| `VALIDATION_FAILED` | 400 | `q` TOO_SHORT (autocomplete, < 2 letters/digits) / INVALID (search, 2–60 chars); search or summary `to` INVALID_RANGE (> 366 days); `month` INVALID (`YYYY-MM`); `groupBy` INVALID; bulk `items` REQUIRED/TOO_MANY (1–500); rule `matchType`/`pattern`/`categoryPath`/`priority`; import `fileName`/`sizeBytes`. |
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | `POST /v1/expenses:bulk` without a key (rules and imports: key optional). |
| `IMPORT_NOT_STARTABLE` | 409 | `POST /v1/imports/{id}:start` on a completed or failed job (create a new import; already imported rows are skipped). |
| `RULE_LIMIT_REACHED` | 409 | The account already has 200 categorisation rules. |
| `NOT_FOUND` | 404 | Unknown/foreign import or rule id; unknown `:action`. |

Bulk items never fail the request: each item is `created`, `duplicate` (its `clientRef` exists — earlier call,
import, or earlier in the same request; the existing id is returned) or `invalid` (field errors with
`items[i].` prefixes, `UNKNOWN_CATEGORY`).

Import job failures (`status: failed`, `errorCode`): `CSV_INVALID` (header must be exactly
`date,amount,merchant,category,payment_method,note,client_ref`; wrong column count; encoding — the detail names
the file line), `FILE_TOO_LARGE` (> 10 MB), `TOO_MANY_ROWS` (> 50,000), `FILE_MISSING` (nothing uploaded),
`IMPORT_FAILED` / `IMPORT_ABANDONED` (worker gave up after 3 attempts). Row errors (`errors[]`, row 1 = first data
row): `DATE_INVALID`, `AMOUNT_INVALID`, `MERCHANT_TOO_LONG`, `CATEGORY_INVALID`, `CATEGORY_UNKNOWN`,
`PAYMENT_METHOD_INVALID`, `NOTE_TOO_LONG`, `CLIENT_REF_TOO_LONG`.

### catalog (P09, `CatalogErrors` + core codes)

| Code | Status | When |
|---|---|---|
| `SLUG_TAKEN` | 409 | `POST /v1/admin/catalog/cards` with a slug another card uses. |
| `ISSUER_EXISTS` | 409 | `POST /v1/admin/catalog/issuers` with an existing code. |
| `CARD_RETIRED` | 409 | Editing, publishing or retiring a retired card (or retiring a draft). |
| `OFFER_WITHDRAWN` | 409 | Withdrawing an offer twice. |
| `ADMIN_TOKEN_REQUIRED` | 403 | A user or service token on `/v1/admin/catalog/*` (the gateway already answers 401 without a token). |
| `FORBIDDEN` | 403 | Missing `catalog:read` / `catalog:write` / `catalog:publish`, or no CATALOG_EDITOR / SUPER_ADMIN role. |
| `VALIDATION_FAILED` | 400 | Card document fields (`slug`, `name`, `issuerCode` UNKNOWN_ISSUER, `network`, `tier`, fees NEGATIVE, rates OUT_OF_RANGE, `features.<key>`, `rules[i].*` incl. DUPLICATE_SCOPE / CAP_SHAPE, `milestone.*`), `slug` IMMUTABLE on draft save; list filters (`network`, `maxAnnualFee`, `category`, `minRateBps` without `category`); search `q` 2–60; offer fields. |
| `IF_MATCH_REQUIRED` / `VERSION_CONFLICT` | 428 / 412 | `PUT /v1/admin/catalog/cards/{id}/draft` without / with a stale draft ETag. |
| `NOT_FOUND` | 404 | Unknown or unpublished slug on the public API (drafts are invisible), unknown card/offer id, unknown `:action`. |
| `CURSOR_INVALID` / `CURSOR_FILTER_MISMATCH` / `LIMIT_INVALID` | 400 | `GET /v1/catalog/cards` keyset paging. |

A publish of an unchanged draft is not an error: it answers the current version with `changed: false`.

### catalog ingestion (P10, `CatalogErrors` + core codes)

| Code | Status | When |
|---|---|---|
| `SOURCE_EXISTS` | 409 | `POST /v1/admin/ingestion/sources` (or a PATCH of `url`) with a URL another live source fetches. |
| `REVIEW_DECIDED` | 409 | `:approve` of a rejected item, `:reject` of an approved or superseded item. (`:approve` of an approved item is not an error: it answers the same promotion with `replayed: true`; `:reject` twice answers the rejected item.) |
| `REVIEW_STALE` | 409 | `:approve` of a superseded item, or of an item whose card moved past its `baseVersion` (an editor published meanwhile): approve the newer item or run the source again. |
| `DRAFT_PENDING` | 409 | `:approve` while the card has unpublished draft edits: publish them or `DELETE /v1/admin/catalog/cards/{id}/draft` first. |
| `DRAFT_NOT_DISCARDABLE` | 409 | `DELETE …/draft` of a card that was never published (the draft is the card). |
| `VALIDATION_FAILED` | 400 | Source fields: `url` refused by the fetch policy (`HOST_NOT_ALLOWED`, `SCHEME_NOT_ALLOWED`, `PORT_NOT_ALLOWED`, `CREDENTIALS_IN_URL`, `BAD_URL`), `kind`, `cardProductId` (UNKNOWN_CARD, OTHER_ISSUER, NOT_ALLOWED on a sitemap), `issuerCode` (REQUIRED, UNKNOWN_ISSUER), `scheduleCron` (five fields, IST, at most hourly); PATCH fields not patchable (UNKNOWN_FIELD); runs `sourceIds` (INVALID, NONE_RUNNABLE, NOT_RUNNABLE); review `status`; reject `reason` > 500. |
| `IF_MATCH_REQUIRED` / `VERSION_CONFLICT` | 428 / 412 | `PATCH /v1/admin/ingestion/sources/{id}` without / with a stale ETag. |
| `INVALID_ID` / `NOT_FOUND` | 400 / 404 | Review item id not a UUID / unknown source, run or item, unknown review `:action`. |
| `FORBIDDEN` / `ADMIN_TOKEN_REQUIRED` | 403 | Reads need `catalog:read`, sources and runs `catalog:write`, approve/reject `catalog:publish`; user and service tokens are refused. |

Fetch failures are not API errors: a run records them per URL in its checkpoint (`errors[].code`: the refusals above
after DNS as `PRIVATE_ADDRESS`, or `UNKNOWN_HOST`, `TIMEOUT`, `NETWORK`, `HTTP_<status>`, `TOO_LARGE`,
`WRONG_CONTENT_TYPE`, `TOO_MANY_REDIRECTS`) and moves on to the next source.

### portfolio (P11, `PortfolioErrors` + core codes)

| Code | Status | When |
|---|---|---|
| `USER_TOKEN_REQUIRED` | 403 | Any portfolio endpoint called with an admin-console or service token: portfolio data belongs to an end-user account. |
| `CARD_EXISTS` | 409 | `POST /v1/cards` for a product + last4 the account already holds (a removed card does not count: it can be added again). |
| `CARD_NOT_ACTIVE` | 409 | Recording a transaction, creating an EMI plan or posting points on a closed or removed card. |
| `CLIENT_REF_TAKEN` | 409 | `POST /v1/cards/{id}/transactions` with a `clientRef` another transaction of the account already carries (with a different Idempotency-Key). |
| `LINK_DECIDED` | 409 | `:confirm` of a rejected suggestion or `:reject` of a confirmed one, or `:confirm` when the transaction or the expense is already confirmed with another partner. Repeating the same decision answers the link. |
| `EMI_NOT_ACTIVE` | 409 | `PATCH /v1/emi-plans/{id}` that changes the status of a closed or foreclosed plan (an ended plan cannot be reopened). |
| `INSUFFICIENT_POINTS` | 409 | `POST /v1/rewards/ledger` `kind: redeem` for more than the card's available (unexpired) points. |
| `VALIDATION_FAILED` | 400 | Card: `cardProductId` (REQUIRED, UNKNOWN_CARD_PRODUCT, PRODUCT_RETIRED), `last4`, `statementDay` (1-28 or 31), `dueOffsetDays` (10-30), `creditLimitPaise`, `openedOn` (IN_FUTURE); transaction: `postedAt`, `amountPaise`, `kind`/`direction`, `description`, `categoryPath`; EMI: `userCardId` (UNKNOWN_CARD), `principalPaise`, `annualRateBps` (0-6000), `tenureMonths` (1-84), `processingFeePaise`, `gstBps` (0-2800), `firstDueDate`, `cardTxnId` (UNKNOWN_TRANSACTION, NOT_A_DEBIT), PATCH `status` (closed/foreclosed); rewards: `kind` (redeem/adjust), `points`, `note` (REQUIRED for adjust); report: `period` (month/year), `month`, `year`. |
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | `POST /v1/cards`, `/v1/cards/{id}/transactions`, `/v1/emi-plans`, `/v1/rewards/ledger` without the header. |
| `IF_MATCH_REQUIRED` / `VERSION_CONFLICT` | 428 / 412 | `PATCH /v1/cards/{id}` or `/v1/emi-plans/{id}` without / with a stale ETag. |
| `NOT_FOUND` | 404 | Unknown or another account's card, transaction, link or plan (RLS: they do not exist for the caller); unknown link `:action`. |

### portfolio statements (P12a, `PortfolioErrors` + core codes)

| Code | Status | When |
|---|---|---|
| `STATEMENT_NOT_UPLOADED` | 409 | `:submit` before the PDF was PUT to the upload URL (or after the 15-minute URL expired without an upload: create a new upload). The Idempotency-Key is released, so a retry with it works once the file is there. |
| `STATEMENT_FILE_REJECTED` | 422 | The scan refused the file; `reason` = `EMPTY_FILE`, `FILE_TOO_LARGE` (10 MB) or `NOT_A_PDF` (no `%PDF-` magic). The upload is `failed` with that code and the quarantined object is deleted. |
| `STATEMENT_ALREADY_UPLOADED` | 409 | `:submit` of a file (same SHA-256) already submitted, parsed or confirmed for the same card; `uploadId` = that upload. After a discard or a failure the file may be uploaded again. |
| `STATEMENT_WRONG_STATE` | 409 | `:submit` of an upload that is not `awaiting_upload` (with another key), `:confirm` of one that is not `parsed`, `:discard` of one that is not `awaiting_upload`/`parsed`; `status` = the current status. Discarding twice answers the upload. |
| `STATEMENT_CARD_MISMATCH` | 409 | `:confirm` when the parser read another card's last four digits from the PDF (`parsed.cardMatches: false`): discard it and upload it for the right card. |
| `OBJECT_STORE_UNAVAILABLE` | 503 | The object store did not answer (`Retry-After: 5`); nothing changed, the same Idempotency-Key works later (a submit that already stored the clean copy resumes from its recovery point). |
| `CARD_NOT_ACTIVE` | 409 | Creating an upload for, or confirming one into, a closed or removed card. |
| `VALIDATION_FAILED` | 400 | Upload: `userCardId` (REQUIRED, UNKNOWN_CARD), `fileName` (1-200), `sizeBytes` (1-10 MB). |
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | `POST /v1/statements/uploads` and `…/{id}:submit` without the header (`:confirm` takes an optional key). |
| `NOT_FOUND` | 404 | Unknown or another account's upload; unknown upload `:action`. |

Parser results are not HTTP errors: a statement the ai-parser cannot read leaves the upload `failed` with
`errorCode` from the parser (`NOT_A_PDF`, `PDF_ENCRYPTED`, `PDF_UNREADABLE`, `TOO_MANY_PAGES`, `NO_TEXT`,
`UNKNOWN_FORMAT`, `NO_TRANSACTIONS`, `TOO_MANY_LINES`, `BAD_PERIOD`, `UNREADABLE_VALUES`, `FILE_UNAVAILABLE`,
`FILE_CHANGED`, `FILE_TOO_LARGE`) or from portfolio's own check of the lines (`BAD_LINES`, `FUTURE_DATED_LINES`).

### planning (P13, `PlanningErrors` + core codes)

| Code | Status | When |
|---|---|---|
| `USER_TOKEN_REQUIRED` | 403 | Any planning endpoint called with an admin-console or service token: a budget, a liability and a scenario all belong to an end-user account. |
| `BUDGET_NOT_ACTIVE` | 409 | `PATCH /v1/budgets/{id}` on an ended budget. An ended budget is read-only — its periods and alerts stay for the history — so a new one is created rather than reopened. |
| `LIABILITY_NOT_ACTIVE` | 409 | `PATCH /v1/liabilities/{id}` that changes the status of a closed liability (a closed one cannot be reopened; its schedule is what the terms implied when it was recorded). |
| `SCENARIO_ADJUSTMENT_INVALID` | 422 | An adjustment the evaluator cannot apply to the scenario's snapshot: an unknown `kind`, or a `budgetId`/`liabilityId` the snapshot does not contain. The snapshot is a copy taken when the scenario was saved, so an id that exists in the account today may not be in it. |
| `VALIDATION_FAILED` | 400 | Budget: `name`, `period` (weekly/monthly/custom), `categoryPath` (an ltree path like `food.dining`), `limitPaise`, `alertThresholds` (1-5 percentages, 1-500), `startsOn`, `endsOn` (on or after `startsOn`; REQUIRED for a custom period), PATCH `status` (active/paused/ended); liability: `kind` (loan/card_emi/bnpl/other), `lender`, `principalPaise`, `aprBps` (0-6000), `tenureMonths` (1-480), `startDate`, PATCH `status` (closed); scenario: `name`, `monthlyIncomePaise`, `adjustments` (at most 20, each with a `kind`). |
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | `POST /v1/budgets`, `/v1/liabilities` and `/v1/scenarios` without the header. `{id}:evaluate` takes no key: it stores nothing. |
| `IF_MATCH_REQUIRED` / `VERSION_CONFLICT` | 428 / 412 | `PATCH /v1/budgets/{id}`, `/v1/liabilities/{id}` or `/v1/scenarios/{id}` without / with a stale ETag. |
| `NOT_FOUND` | 404 | Unknown or another account's budget, liability or scenario (RLS: they do not exist for the caller); unknown scenario `:action`. |

A threshold alert is not an HTTP error: crossing one writes a row in `planning.budget_alerts_sent` and publishes
`planning.budget.threshold-crossed` once per threshold per period, which the notification service consumes.
