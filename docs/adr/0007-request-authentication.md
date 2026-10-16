# ADR-0007: Request authentication without the Spring Security filter chain

**Status:** Accepted (P02b, 2026-09-27) · proven 2026-10-04 (`verify.sh full` 17/17: `JwtVerifierTest` 20,
`SecurityInterceptorTest` 10, `ServiceTokenClientTest` 5, `DemoAuthIT` 5)

## Context
MASTER_PLAN 7.2 wants zero trust: every service validates the access token itself, derives
`AuthContext` → `Tenant` for RLS, and enforces `@RequiresPermission` / `@RequiresStepUp`. The stack
table lists Spring Security. Adding `spring-boot-starter-security` (or the OAuth2 resource-server
starter) to a service turns on Boot's security auto-configuration: every endpoint including actuator
and the dev lab is locked, errors are written by Spring Security's entry points instead of
`ProblemDetailsAdvice`, and custom annotations need method-security proxies plus templated
`@PreAuthorize` meta-annotations. All of that is more moving parts than the contract needs.

## Decision
1. **Verification with Nimbus JOSE+JWT directly** (`JwtVerifier`, pinned 10.9.1), the same library
   Spring Security's resource server uses internally.
   - Algorithm allowlist: **ES256 only** (JCA; EdDSA in Nimbus needs Google Tink). `none` and HMAC
     tokens are refused before any key is looked up, so key-confusion attacks cannot work.
   - Keys come only from identity's JWKS by `kid`; `jwk`, `jku`, `x5u`, `x5c` headers are refused.
   - `typ` must be `at+jwt` (RFC 9068), so an ID or refresh token can never be used as an access token.
   - `iss` exact, `aud` contains this service, `exp`/`nbf`/`iat` with 30 s skew, `exp - iat ≤ 15 min`.
2. **JWKS cache** (`JwksCache`): 5 min TTL, refetch on unknown `kid`, at most one fetch per 10 s of any
   kind (flood and outage protection), last good keys usable for 1 h, then 503.
3. **Enforcement in a Spring MVC `HandlerInterceptor`** (`SecurityInterceptor`), default deny:
   - It runs inside the DispatcherServlet, so its exceptions become coded ProblemDetails like
     every other error, and actuator (own handler mapping) is untouched.
   - `@PublicEndpoint` opts out; configured public paths (`/_dev/**`, `/error`) apply only to
     handlers without security annotations.
   - Caller kinds: `@InternalEndpoint(callers)` accepts only service tokens; every other handler
     rejects them.
   - `@RequiresPermission` (all listed), `@RequiresStepUp` (RFC 9470 challenge, 401 `STEP_UP_REQUIRED`).
4. Controllers receive `AuthContext` or `Tenant` as a parameter (argument resolver). `Tenant` is always
   role `USER`; admin code picks its admin role with `AuthContext.tenantAs(role)` (least privilege).
5. **Service-to-service:** `ServiceTokenClient` (client-credentials, Basic client auth, one token per
   audience, cached to `exp - 60 s`, single flight per audience).
6. The reactive gateway (P06) reuses `JwtVerifier`/`JwksCache` (framework-free) for its early rejection.

## Consequences
- Method security on app-layer beans is not available; authorization happens at the controller
  boundary plus admin RLS policies in the database (defense in depth, MASTER_PLAN 7.2). ArchRules can
  forbid `@RequiresPermission` outside `..api..`.
- Spring Security is still used where it adds value without the filter chain:
  `spring-security-crypto` for Argon2id in P04.
- Identity (P04) must issue tokens to the contract in `TokenClaims` (amendment A-18).
