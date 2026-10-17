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

Shared codes identity uses: `VALIDATION_FAILED` with `errors[].code` = `INVALID_EMAIL`, `REQUIRED`, `TOO_SHORT`,
`TOO_LONG`, `BREACHED`, `CONTAINS_EMAIL` (passwords), `INVALID_TZ`, `INVALID_LENGTH`, `INVALID_CHARACTERS`,
`INVALID_PHONE`; `UNKNOWN_FIELD` for a merge-patch member that cannot be changed; `IF_MATCH_REQUIRED` /
`IF_MATCH_INVALID` / `VERSION_CONFLICT` on `PATCH /v1/me`; `IDEMPOTENCY_*` on registration; `STEP_UP_REQUIRED` on
`logout-all` and `password/change`; `SERVICE_UNAVAILABLE` + `Retry-After: 1` when the Argon2 bulkhead is full.
Registration and forgot-password never fail on the email's existence: always `202 {"status":"accepted"}`.


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
