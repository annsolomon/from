# ADR-0009: Identity — pre-auth SQL, refresh-token families, signing keys, envelope encryption

- Status: Accepted (P04). Proof: identity ITs (`AuthFlowIT`, `EnumerationSafetyIT`, `JwksRotationIT`, `ServiceTokenIT`,
  `ProfileIT`, `IdentityRlsIT`, `RegistrationIdempotencyContractIT`, `SessionsPaginationContractIT`), `api-smoke` P04
  block, `trail-check` P04 checks.
- Context: MASTER_PLAN P04 and 7.1 ask for registration, login, sessions and rotating refresh tokens with family
  reuse detection, EdDSA/ES256 JWTs with JWKS rotation, Argon2id, a breached-password list, enumeration safety,
  per-account data keys, and RLS on every tenant table — while 3.2 says pre-authentication lookups go through narrow
  `SECURITY DEFINER` functions and every unit of work runs in `TenantTx`.

## Decisions

1. **Pre-authentication SQL = six `SECURITY DEFINER` functions** owned by `identity_owner`, `search_path` pinned,
   minimum columns, `EXECUTE` only for `identity_app`: `auth_find_login`, `auth_register`, `auth_find_refresh`,
   `auth_verify_email`, `auth_reset_password`, `auth_find_account`. Multi-step pre-auth writes (activate on verify;
   new hash + revoke every session on reset; create account + verification token on register) are single functions,
   so they are atomic without an account context.
2. **Owner policies.** With `FORCE ROW LEVEL SECURITY` the owner is subject to RLS too, so each identity table has a
   `<table>_owner ... TO identity_owner USING (true)` policy. This grants nothing new — the owner could disable RLS on
   its own tables at any time — and the owner never connects at runtime (Flyway, the seed loader and the definer
   functions only). `identity_app` stays strictly tenant-bound.
3. **Synthetic tenants** (`IdentityTenants`): `SYSTEM` (nil UUID, role `SYSTEM`) for global work and the definer
   calls; `client(clientId)` (name-based UUIDv3, role `PREAUTH`) as the Idempotency-Key scope of registration
   ("per client id", MASTER_PLAN 5.1). Neither can collide with an account id (UUIDv7) and neither matches any tenant
   row, so RLS still shows them nothing.
4. **Registration fingerprint excludes the password.** The Idempotency-Key fingerprint is
   `{email, displayName, tz, clientId}`. A fast SHA-256 over a body containing the password, stored for 24 h, would
   be an offline-guessable password hash. Same key + same email with another password replays the 202 (harmless).
5. **Refresh tokens**: opaque 256-bit, only SHA-256 stored; one family per session; rotation on every use with
   `SELECT … FOR UPDATE` on the presented row (concurrent refreshes of one token serialise); 30-day sliding window
   capped at a 90-day absolute limit. **Reuse is strict**: any presentation of a rotated token revokes the family and
   the session, emits `identity.session.revoked` + `identity.security.alert`, and answers 401
   `REFRESH_TOKEN_REUSED`. The revocation commits *before* the error is returned (the use-case returns a result, the
   controller layer throws). A client that races two refreshes of the same token is signed out; that is the price of
   detection and is documented for app developers (refresh single-flight per device).
6. **Access tokens** follow A-18 exactly (ES256, `typ=at+jwt`, `kid`; `iss`, `sub`, `aud`=all user-facing services,
   `client_id`, `sid`, `scope=user`, `roles=[USER]`, `amr=[pwd]`, `auth_time` (epoch seconds, survives refresh),
   `mfa_enrolled`, `tz`, `tier`), 10-minute TTL. Identity verifies its own endpoints with the same `JwtVerifier`,
   fed from its key table instead of HTTP.
7. **Signing keys `next → active → retired`.** There is always a published `next` key, so every service already
   has the key that becomes active at rotation (no unknown-kid window). Rotation every 30 days under a
   transaction-scoped advisory lock (one instance acts); retired keys stay published for 2 h (> 15-min max token
   lifetime + 5-min JWKS cache + skew), then are deleted. Private JWKs are AES-256-GCM-wrapped by the KEK
   (aad `signing-key:<kid>`).
8. **Envelope encryption** (MASTER_PLAN 7.4): per-account random DEK in `account_keys`, wrapped by the KEK
   (aad `dek:<account>`), created on first use (seeded accounts have none); fields are AES-256-GCM under the DEK with
   aad `<account>:<field>` so a ciphertext copied to another row or column fails. Uniqueness on encrypted values uses
   an HMAC-SHA256 blind index with a server pepper (`phone_hash`). KEK and pepper come from the environment (dev:
   `.env.example`; prod: Vault, P19).
9. **Argon2id** m=19 MiB, t=2, p=1 (the seed's parameters, ADR-0008) behind a semaphore bulkhead (default 8 permits,
   5 s wait → 503 + `Retry-After: 1`), rehash on login when parameters change, a dummy-hash verification for unknown
   emails. Breached list: the NCSC top-100k (HIBP-derived, SecLists, MIT) filtered to entries ≥ 10 chars,
   case-insensitive, bundled gzip (see `breached-passwords.NOTICE`).
10. **Security email is sent after commit** (asynchronous, bounded, body never logged). A rolled-back or replayed
    request sends nothing. There is no durable mail queue in P04: every flow is retryable by the user (register
    again → a fresh link, at most 5 per hour; forgot again). A durable notification queue is P18.
11. **Service tokens** at `POST /oauth2/token` (RFC 6749 client credentials, HTTP Basic, `audience` form param),
    5-minute TTL, `sub=svc:<name>`, one audience. Clients and their allowed audiences are configuration; secrets are
    held as SHA-256 digests and compared in constant time. The Basic header is parsed by a config-layer argument
    resolver — the only Authorization-header read outside dexter-security (ArchRules forbid it in api/app/infra).

## Consequences

- P05 replaces `auth_find_login` (MFA flag from `mfa_totp`), adds lockout (Redis) and admin tables on the same
  patterns; the access-token builder already carries `mfa_enrolled`/`amr`.
- P06 forwards the client IP (today sessions record the socket address) and adds rate limits in front of the
  pre-auth endpoints (the mail throttle is per account only).
- P18's privacy delete saga crypto-shreds by destroying the `account_keys` row.
