# ADR-0004: Redis cache invalidation

- **Status:** Accepted · **Date:** 2026-09-21 · **Phase:** P00 (design); built in P02b (`dexter-cache`, 2026-10-04, see §12)
- **Extends:** MASTER_PLAN 8.2 ("Redis cache-aside with versioned keys, single-flight, probabilistic early expiry").
  8.2 says how entries are *built*. This ADR says how and when they become *invalid*.

## 1. Principles

1. **Postgres is the source of truth; Redis is disposable.** Every cached read has a DB fallback. A Redis error
   (200 ms timeout, MASTER_PLAN 8.2) means *bypass the cache*, never *fail the request*.
2. **Invalidate by generation, not by delete.** Keys embed a generation number. A change bumps the generation
   (`INCR`), so old entries become unreachable immediately and expire through their TTL. There is no
   `DEL`-then-repopulate race and no key scanning on the hot path.
3. **Invalidate only after commit.** Never before, and never inside the DB transaction.
4. **Two invalidation paths:** an immediate one after commit, and a durable one driven by the outbox.
   If the first is lost, the second arrives. If both are lost, the TTL is the hard upper bound on staleness.
5. **Tenant isolation holds in the cache too.** Every tenant key contains the account id. There is no
   cross-tenant cache entry, ever.

## 2. Key scheme

```
gen key : gen:{svc}:{<accountId>}:<scope>                       e.g. gen:ledger:{0192…}:summaries
value   : {svc}:v<schema>:{<accountId>}:<scope>:g<gen>:<paramsHash>
global  : gen:catalog:global:rules  →  catalog:v1:global:rules:g<gen>:<hash>
```

- `{<accountId>}` is a Redis Cluster hash tag. All of an account's keys and its gen counters live in one slot,
  so the read script below is single-slot.
- `v<schema>` is bumped in code when the cached payload shape changes. The old shape then simply stops being read,
  so no flush is needed on deploy.
- `<paramsHash>` is the first 16 hex characters of the SHA-256 of the canonical query parameters (for example month=2026-09, tz).
- A **scope** is a small, named invalidation unit per service. Examples: `summaries`, `calendar`, `profile`,
  `reco`, `entitlements`, `rules`. A write bumps exactly the scopes it affects. The scope map lives in each
  service's `config/CacheScopes.java` and is documented in the phase's Query→Index table.

## 3. Read path (one round trip)

A Lua script reads the generation and then the value under that generation, returning both:

```lua
-- KEYS[1] = gen key, ARGV[1] = value key prefix up to ":g", ARGV[2] = params hash
local g = redis.call('GET', KEYS[1]) or '0'
local v = redis.call('GET', ARGV[1] .. ':g' .. g .. ':' .. ARGV[2])
return {g, v}
```

- **Hit:** return the value. Apply probabilistic early expiry (XFetch): with a probability that rises as the
  TTL runs out, rebuild in the background before expiry.
- **Miss:** take a single-flight lock with `SET lock:<valuekey> <token> NX PX 5000`, load from the DB through TenantTx,
  write with `SET … PX <ttl±10% jitter>` under the **generation read before loading**, then release the lock
  (compare-and-delete by token). Other readers that lose the lock wait up to 150 ms for the value,
  then go to the DB themselves. No request waits on Redis longer than that.
- **Why the race is harmless:**
  1. A reader reads generation 5.
  2. A writer commits and bumps the generation to 6.
  3. The reader, still holding old data, writes it under `g5`.
  4. Every new reader asks for `g6` and never sees that stale `g5` entry, which then expires.
  With delete-based invalidation, the same interleaving poisons the cache until the TTL runs out.
- **Negative caching:** a `NOT_FOUND` marker with a TTL of 30 s, under the same generation rules.

## 4. Invalidation paths

**Path A — immediate, same service, after commit.**
Use cases in the `app` layer declare the scopes they touch. `CacheInvalidator.afterCommit(account, scopes…)`
registers a `TransactionSynchronization`. After commit it runs `INCR` on each gen key, pipelined, and sets a
`PEXPIRE` of 7 days on the gen key so abandoned accounts don't accumulate keys. Failures are logged and
counted (`cache_invalidation_failures_total`), never thrown: the commit has already happened.

**Path B — durable, driven by the outbox (at-least-once).**
Every outbox event already carries `accountId`, `type` and `aggregateVersion` (MASTER_PLAN 6.1).
- Each service runs an **invalidation consumer** on the topics it caches data from, including its own topics.
  The consumer maps `event type → scopes` and bumps them.
- The consumer goes through the inbox, so duplicates are skipped. Even without the inbox, an extra `INCR`
  is harmless, because an extra bump only costs one miss.
- This path covers:
  - a crash between commit and Path A;
  - a Redis blip during Path A;
  - **cross-service caches.** For example, reco caches that depend on ledger expenses and catalog rules
    are invalidated when reco's projection consumer applies the event. The bump happens in the projection's
    own after-commit hook, so a reader never sees the new generation before the projection holds the new data.

**Path C — TTL, the hard bound.** Every value has a TTL. Default TTLs:

| Scope | TTL | Why |
|---|---|---|
| `reco` (best card, wallet) | 30 min | Recomputed on events; the TTL only bounds lost-event staleness |
| `summaries`, `calendar` | 10 min | Backed by aggregate tables; cheap to rebuild |
| `profile` (spend profile) | 30 min | Projection-driven |
| `entitlements` | 60 s | Affects authorization, so it is kept short even with events |
| `rules` (global catalog) | 15 min | Changes only on promote/publish |
| negative (`NOT_FOUND`) | 30 s | |

## 5. Global reference data (catalog rules, categories, merchants)

- One global generation per scope, bumped when `catalog.rules.published` (and similar events) are applied.
- An optional **L1 in-process cache** (Caffeine, max 60 s, size-bounded) is allowed **only** for global data.
  To drop L1 across instances quickly, the bumping instance publishes `PUBLISH cache:inv:{svc} <scope>:<gen>` on Redis pub/sub.
  Pub/sub is fire-and-forget, so the 60 s L1 TTL is the bound, and the Redis generation check is still authoritative on the L2 path.
- L1 is **never** used for tenant data. That keeps the number of places where one tenant's data could leak small and auditable.

## 6. Authorization-relevant data (entitlements, plan tier)

- `billing.entitlement.changed` → consumers bump `entitlements` for that account through Path B.
  Billing itself bumps through Path A.
- The JWT `tier` claim is at most 10 minutes old (the access-token TTL). Endpoints gated on a paid feature
  check entitlements through the cache (60 s TTL) or DB, **not** the claim, when upgrade/downgrade latency matters.
- If Redis is down, entitlements are read from the DB. They are **never** assumed from a stale cache or the claim.

## 7. Privacy delete (P18, DPDP)

- The deletion saga bumps every scope for the account (Path A in each service, triggered by `audit.privacy.delete.requested`).
- It then removes the account's keys: `SCAN 0 MATCH *:{<accountId>}:* COUNT 500` + `UNLINK` in each service's
  key space. This is a rare operation, and the hash tag keeps the scan to one slot on Cluster. It also deletes the gen keys.
- The saga step completes only after `UNLINK` is acknowledged. The TTL remains the backstop, and the longest
  tenant TTL (30 min) is recorded in the privacy runbook as the worst-case cache residency.

## 8. Security

- Redis ACL per service: user `ledger` with `~ledger:* ~gen:ledger:* ~lock:ledger:* &cache:inv:ledger`, plus only the
  commands the library needs (`+get +set +incr +pexpire +eval +evalsha +unlink +scan +publish +subscribe`).
  No `KEYS`, no `FLUSH*`, no `CONFIG`. This is added to the dev compose file in P02, and to the ops runbook.
- Cached values are serialized DTOs, never entities or credentials. Tokens, refresh tokens, TOTP secrets and
  password hashes are never cached.
- Cache keys never contain raw PII (email, phone); only UUIDs and parameter hashes.

## 9. Library: `libs/dexter-cache` (built in P02, alongside `dexter-events`)

- `CacheScope`, `CacheKey`, `TenantCache.get(Tenant, scope, params, Class<T>, loader)`, `GlobalCache`,
  `CacheInvalidator` (Path A), `InvalidationConsumer` base class (Path B, inbox-aware), plus Micrometer metrics
  `cache_requests_total{result=hit|miss|bypass|stale_refresh}`.
- No JPA, no Spring Data Redis repositories. It uses the Lettuce client through `spring-boot-starter-data-redis` templates only.
- ArchUnit rule: only `app`-layer classes may call `CacheInvalidator`. Controllers never touch the cache.

## 10. Required tests (added to `dexter-test`, used by every phase that caches)

1. Write → read returns the new value (Path A).
2. Path A fails (Redis stopped after commit) → the Path B event bumps the generation → the next read is fresh.
3. The reader/writer interleaving from §3, forced with latches → no stale read after the writer commits.
4. Redis down → reads bypass to the DB, and the result is still correct.
5. Tenant isolation: A and B with the same params → different keys; A can never read B's entry.
6. Single-flight: 50 concurrent misses → exactly 1 DB load (counted).
7. Privacy delete → no keys matching the account remain.
8. Metrics exposed; the `bypass` count increments during a Redis outage.

## 11. Consequences

- A cached read costs one extra `GET` (the gen key), inside a single script round trip.
- Invalidation logic lives in one library and one scope map per service. Correctness does not depend on
  every call site remembering to delete keys.
- Phases that cache: P07/P08 (ledger summaries, calendar), P09/P10 (catalog rules, global), P13 (budget period spend),
  P14/P15 (reco, spend profile), P16 (entitlements), P18 (privacy delete). Each phase lists its scopes and TTLs in its section of BUILD_README.

## 12. Implementation notes (P02b, 2026-10-04)

Built in `libs/dexter-cache`; deviations and precisions, recorded as plan amendments A-20..A-22:

- **All Redis access is Lua** (`RedisCacheStore`): read (gen + value + PTTL), write (`SET PX`), lock (`SET NX PX`),
  unlock (compare-and-`UNLINK`), bump (`INCR` + `PEXPIRE` per key). Purge uses `SCAN MATCH COUNT 500` + `UNLINK`.
  The ACL therefore allows `eval evalsha get set incr pexpire pttl unlink scan` (+ `publish subscribe ping info
  client|setinfo client|setname`). No `DEL`, `KEYS`, `FLUSH*`, `CONFIG`. Template: `infra/redis/acl.tmpl`.
- **Single flight is two-level**: callers in one JVM join one future; its leader takes the Redis lock and
  re-reads once before loading (so a leader that arrives just after another finished does not load again).
- **Breaker**: after a Redis error, reads skip Redis for `breakerOpen` (1 s), so an outage costs one timeout, not one
  per request. Every skipped read counts as `bypass`.
- **XFetch** uses the remaining PTTL from Redis and the measured load time stored with the value
  (`<loadMs>:J<json>` / `<loadMs>:N`); beta 1.0.
- **Reads inside a transaction are rejected**: the loader opens its own read-only `TenantTx`; a loader joining a write
  transaction could cache uncommitted rows.
- **The tenant's role is part of the parameter hash**, so an admin acting on an account never shares the owner's entry.
- **Path B consumers do not use the inbox** (A-20, revises §4 "goes through the inbox"): a duplicate bump only costs a
  miss, while "inbox row committed, then Redis unreachable" would lose the bump. `InvalidationConsumer` bumps
  synchronously and throws on failure, so the event goes through the retry topics.
- **Who may call `CacheInvalidator`** (A-21, revises §9 "only app"): app use-cases (Path A) and event consumers
  (`EventConsumer.Handler`; projections bump from their own transaction). Enforced by `ArchRules`.
- **Privacy purge deletes gen keys too** (§7). A deleted account receives no further bumps, so a counter restarting at
  0 cannot make an old entry reachable again; values are removed in the same purge.
- **No L1** for global data yet (§5 allows one); P09 adds it if the catalog needs it.
- Proven by: `TenantCacheIT`, `RedisAclIT`, `DemoCacheContractIT` (the §10 list + negative caching),
  `DemoCacheInvalidationIT` (outbox → relay → Kafka → bump, including a Redis outage during Path B).
