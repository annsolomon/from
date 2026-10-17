# ADR-0010: Identity B — TOTP MFA, Redis throttles, admin console, RBAC as data, break-glass

- Status: Accepted (P05). Proof: `TotpTest` (RFC 6238 vectors), `RecoveryCodesTest`, `RedisLoginThrottleIT`,
  `MfaFlowIT`, `LockoutIT`, `AdminIT`, `AdminRlsIT`, `IdentityRlsIT` (mfa tables), `api-smoke` P05 block,
  `trail-check` P05 checks.
- Context: MASTER_PLAN P05 and 7.1/7.2 ask for TOTP with recovery codes, step-up, progressive lockout, admins with
  mandatory MFA, roles → permissions in the token, and a time-boxed SUPER_ADMIN break-glass with a reason — while
  3.1 keeps a separate `identity_admin` role for admin access to other people's data.

## Decisions

1. **TOTP = RFC 6238 defaults** (HMAC-SHA1, 30 s, 6 digits; what authenticator apps assume). Verification accepts
   ±1 step and returns the matched step; `last_used_step` only moves forward (`UPDATE … WHERE last_used_step < :s`),
   so a code is accepted **once** — also across sign-in, step-up and break-glass. Enrolment is pending until the
   first code confirms it. User secrets are encrypted under the account DEK (field `totp`); admins have no DEK, so
   admin secrets are KEK-wrapped with aad `admin-totp:<id>`.
2. **Recovery codes**: 10 × `xxxxx-xxxxx` (31-symbol alphabet, ≈ 49.5 bits), shown once, stored as the keyed blind
   index (HMAC with the server pepper), single use (`used_at`). Using one raises `RECOVERY_CODE_USED`.
3. **Two-step sign-in**: with MFA enrolled, the password answers `{mfaRequired, mfaToken, expiresIn}` instead of
   tokens. The challenge lives in Redis (`identity:mfa:<sha256(token)>`, 5 min, 5 wrong codes burn it) and is
   consumed by one `GET`+`UNLINK` script, so exactly one request wins. Tokens after MFA carry `amr=[pwd,mfa,otp]`
   (or `[pwd,mfa]` with a recovery code, RFC 8176).
4. **Step-up** (`POST /v1/auth/step-up`): password + second factor when enrolled → a fresh access token; the
   session's `auth_time`/`amr` are updated so refreshed tokens keep the recent sign-in. Removing MFA or regenerating
   codes additionally needs `amr=mfa`: a recent token from *before* enrolment cannot strip MFA.
5. **Throttles in Redis, fail closed.** Keys: sha256 of the lower-cased email text (unknown emails are throttled
   like known ones — no enumeration through 429), account/admin id for MFA and step-up, and client IP. Policy: 3
   free failures, then `1 s·2^n` (≤ 60 s) since the last failure, lock 15 min after 10; per IP 100 failures per
   15 min. Each operation is one Lua script with the current time as an argument (testable without sleeping) using
   only the commands the per-service ACL allows. Redis unreachable → 503 `Retry-After: 2` (never an unthrottled
   password check). A correct password does not lift an active lock. `ACCOUNT_LOCKED` is raised once per lock.
6. **Admins are not accounts.** `identity.admins` with its own sessions/refresh tokens (same rotation and reuse
   rules, 12 h idle / 7 d absolute) and its own client (`dexter-admin`). Admin tokens have `scope=admin`, roles,
   `perms`, `amr` with `mfa`, and `mfa_enrolled=true`. `AdminGuard` rejects user tokens on admin endpoints and admin
   tokens on user endpoints. MFA is mandatory (`mfa_required CHECK (true)`): an admin without TOTP must enrol it
   inside the sign-in (setup step) before any token exists.
7. **RBAC is data** (`roles`, `permissions`, `role_permissions`, seeded by the migration); tokens carry the
   resolved permissions and handlers declare `@RequiresPermission`. Database policies check the actor role again.
8. **SUPER_ADMIN is never standing.** It is held only through `identity.elevations`: eligible admin, reason 20–500
   chars, a fresh TOTP code, ≤ 1 h (table CHECK), one at a time. The token carrying SUPER_ADMIN expires no later than
   the elevation, and every SUPER_ADMIN use-case re-reads the elevation row, so ending it early (or the hour passing)
   takes effect immediately. RLS refuses an elevation row for an ineligible or inactive admin.
9. **Two pools.** Admins acting on themselves use `identity_app` (tenant = admin id; may update only its own
   `password_hash`). Admin endpoints acting on others use a second Hikari pool as `identity_admin` with
   `app.actor_role` = the least-privileged role that suffices; reads go through `*_admin_read` policies, writes to
   admins only through the audited definer functions `admin_create` / `admin_update` (custom SQLSTATEs `DX4xx`
   mapped to API errors). The pool is a holder object, not a `DataSource` bean, so Boot's single-DataSource setup is
   untouched.
10. **Audit**: `identity.admin_audit` (append-only, local) plus the `identity.admin.action` v1 event for P18's
    hash-chained log; lookups are audited even when nothing is found (email masked).
11. **`identity.security.alert` v2** (A-38) adds `ACCOUNT_LOCKED`, `MFA_ENABLED`, `MFA_DISABLED`,
    `RECOVERY_CODE_USED`, `RECOVERY_CODES_REGENERATED`; there were no v1 consumers.

## Consequences

- An admin's token stays valid for its ≤ 10 minutes after the admin is disabled (sessions and refresh tokens are
  revoked at once); only SUPER_ADMIN is re-checked per request. P18's audit service may add a revocation list.
- `approved_by` on elevations is reserved for a two-person rule (not required by the plan).
- P06 forwards the real client IP; until then the per-IP bucket sees the socket address (the trail proxy / curl).
