# ADR-0011: Gateway — route table as code + doc, WebFilter edge chain, Redis token bucket, activity without outbox

- Status: Accepted (P06). Proof: `RouteDocsTest`, `RouteTableIT`, `EdgeFiltersIT`, `RateLimitIT`, `ResilienceIT`,
  `ActivityIT`, `DegradedIT`, `ClientIpTest`, api-smoke P06 block, trail-check P06.
- Context: MASTER_PLAN P06 asks for routes to every service with every path documented and tested (the old
  404/401 bugs), a fixed filter order (request-id, header strip, body limit, JWT pre-check, rate limit, breaker,
  security headers, CORS), an SSE route without breaker or timeout, and sampled, never-blocking activity events.

## Decisions

1. **One route table, three consumers.** `RouteTable` (pure Java) lists each service's path patterns; the gateway
   builds its Spring Cloud Gateway routes from it, the edge filters match with the same patterns (`RouteMatcher`),
   and `docs/api/routes.md` documents each pattern with a concrete example. `RouteDocsTest` makes the doc and the
   code identical; `RouteTableIT` sends every example through the real gateway to WireMock backends. External paths
   equal service paths — no prefix rewriting anywhere (the trail's nginx strips `/api`).
2. **Ownership decided where the plan overlaps**: `/v1/calc/**` → portfolio (P12's calc library; planning's
   "`POST /v1/calc/emi`" is served there), `/v1/webhooks/affiliate/**` → commerce before `/v1/webhooks/**` → billing,
   `/v1/admin/{auth,me,admins,accounts,elevations}` → identity, `/v1/admin/{catalog,ingestion,review}` → catalog,
   `/v1/admin/{audit,activity}` → audit, notifications/privacy → audit (A-42).
3. **Edge rules are WebFilters, not route filters**, so they also cover paths without a route: an unknown path is a
   404 problem with a request id and security headers, never a 401. Only the circuit breaker and timeouts are
   per-route. Order is fixed in `FilterOrder`; CORS runs before the token check so preflights never need a token.
4. **JWT pre-check reuses dexter-security's `JwtVerifier`** (dexter-web excluded; the servlet wiring is conditional
   on a servlet app). Audience `gateway` — identity now adds it to every user/admin token. Verification runs on
   `boundedElastic` (a JWKS refresh is blocking HTTP). Public routes (sign-in flows, JWKS, public catalog/plans/
   deals/ads reads, provider webhooks, the SSE stream with its ticket) skip the check; services still decide.
   Service tokens are refused at the edge (403).
5. **Rate limit = token bucket in one Lua script**, state `"<tokens>:<ms>"` in a plain string (the per-service ACL
   allows GET/SET and scripts, not hashes or `TIME`), time passed as an argument. Key: class + verified account, else
   class + client IP. Headers follow the IETF httpapi draft (`RateLimit-Policy: "read";q=600;w=60`,
   `RateLimit: "read";r=599;t=1`). Redis down: `auth` class fails closed (503), all others fail open. 200 ms Redis
   timeout. The Redis health indicator is off (the edge must stay in rotation without Redis).
6. **Client IP** = right-most `X-Forwarded-For` hop that is not a trusted proxy (loopback + private ranges by
   default), else the socket address; hops a client adds itself are never believed. Identity sets
   `server.forward-headers-strategy=native`, so behind the gateway its per-IP throttle and session IPs see the client.
7. **Circuit breaker per service** (Resilience4j via Spring Cloud CircuitBreaker): counts exceptions and timeouts,
   not 5xx responses (a service's own error body passes through unchanged). Time limiter above the route's 10 s
   response timeout, so the Netty timeout (504) fires first. The SSE route has no breaker and response timeout -1.
8. **Activity without an outbox.** The gateway has no database; events go through a plain Kafka producer owned by
   one daemon thread behind a bounded queue (full → drop + count). `publish` never blocks a request; the topic
   `dexter.gateway.activity.v1` is created on first use (auto-create is off). Envelope = EventEnvelope v1 JSON,
   type `gateway.activity.recorded` v1 with the matched route *pattern* (never the raw path). Sample rate 10 %
   (dev 100 %).
9. **Security headers** set before commit on every response: nosniff, `Referrer-Policy: no-referrer`,
   `X-Frame-Options: DENY`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'; …` (the API never
   serves documents), CORP same-site, HSTS except in dev.

## Consequences

- Adding an endpoint under an existing prefix needs nothing; a new prefix needs a `RouteTable` pattern plus a
  `routes.md` row (the tests fail otherwise).
- P15's SSE through the trail nginx will need `proxy_buffering off` on `/api/` for that route.
- P19 (resilience): the fail-open/closed split is already in place; OWASP ZAP runs against this gateway.
