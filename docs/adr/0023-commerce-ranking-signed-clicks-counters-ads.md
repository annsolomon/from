# ADR-0023: Commerce — a ranked marketplace, signed clicks counted once, idempotent postbacks, capped ads

- Status: Accepted — proven by CI run 42502207781 (git `ed71c5d`): `verify.sh full` 22/22 + `trail-check.sh` PASS (BUILD_README §3).
- Context: MASTER_PLAN P17 asks for deals with savings components (each capped separately; ₹75,000 → ₹7,000),
  ranking `score = 0.8·relevance + 0.2·sponsor` with `relevance = 0.6·cardMatch + 0.4·savingsNorm` and card-match as
  a ranking branch (not a gate), a keyset feed, signed redirect links with raw clicks (partitioned) and Redis
  counters flushed every 10 s, idempotent affiliate postbacks, card applications with a status history (one open
  per card), and ads (campaigns, creatives, placements, partitioned impressions, frequency caps in Redis, weighted
  rotation, ad-free through entitlements). Named tests: savings golden, postback replay, counter flush correctness
  under concurrency, frequency cap, duplicate application.

## Decisions

1. **Savings are integer and per component.** A component is `percent` (bps of the spend, capped at `capPaise`) or
   `flat` (paise, never more than the spend), each with an optional minimum spend; every component is evaluated on
   the *full* spend and capped on its own, the total is their sum capped at the spend (`Savings`). The feed shows a
   deal's saving at its own `reference_spend_paise`; `GET /v1/deals/{id}?spendPaise=` gives it at any spend.
2. **The score is integer basis points:** `(48·cardMatch + 32·savingsNorm + 20·sponsor) / 100`, each term 0..10000
   (`DealRanking`). cardMatch is 10000 when the viewer holds one of the deal's eligible cards, 5000 when the deal is
   open to any card, 0 otherwise — so an anonymous viewer, or one without the card, still sees every deal.
   savingsNorm is the deal's saving over the best saving in the candidate set; sponsor is `sponsor_weight·100`.
3. **The feed ranks in memory over a bounded active set** (at most `feed.max-active` = 500 deals read on
   `deals_active`), ordered by (score DESC, priority DESC, id ASC — the id's text, i.e. unsigned byte order), and
   pages it with the platform's signed keyset cursor over those three keys, bound to the category filter. The order
   is total, so a page never repeats or skips a deal of an unchanged set. A score is personal, so it cannot be an
   index key; at this scale an in-memory rank of ≤ 500 rows per request is cheaper than any precomputation.
4. **Public routes take an optional token, verified.** `@PublicEndpoint` skips the security interceptor, so config's
   `ViewerResolver` reads `Authorization` and verifies it with the service's `JwtVerifier`: no header = anonymous; a
   header that does not verify = 401 (a stale client refreshes rather than silently losing personalisation); a
   verified admin or service token = anonymous. The account comes only from the verified token.
5. **The click link is the credential.** `/r/{token}`: 48 bytes (version, kind deal|ad, target id, viewer account or
   zeros, expiry, nonce) + a 16-byte truncated HMAC-SHA256, base64url (86 chars), valid 7 days. The redirect trusts
   nothing else: the click is attributed to the account *in the token* (who was shown the link), so a forwarded link
   stays the original viewer's and the redirect request needs no header. Altered → 400, expired → 410, a deal that
   ended → 410 and no click is logged.
6. **One click = one raw row + one increment.** The raw row (`deal_clicks`, daily partitions, id a UUIDv7 and
   `created_at` exactly its timestamp, salted SHA-256 of ip and user agent) is written in a TRACKER transaction;
   after commit, one Lua script does `INCR` the click counter and, when `SET NX` (48 h) on the visitor-day mark
   succeeds, `INCR` the unique counter. The 302 carries `subid=<click id>` for the affiliate network.
7. **The counters drain atomically, so concurrent flushers never double count.** The service's Redis ACL allows
   get/set/incr/pexpire/unlink/scan/eval only (no sets, no GETDEL), so every compound step is a Lua script of those:
   the flusher SCANs `commerce:cnt:*`, drains each key with get-and-unlink in one script, and writes one TRACKER
   transaction of additive upserts into `deal_click_counters` / `ad_counters`; if that transaction fails, the batch's
   deltas are added back (another script). An increment therefore lands in exactly one drain, on any number of
   instances, without a leader. A crash between a drain and its commit loses at most that batch (≤ 10 s of counts;
   the raw rows remain the source of truth — §7 of the README lists the reconcile as a gap).
8. **Postbacks are signed and idempotent by (network, provider_ref).** `Postback-Signature` is billing's webhook
   scheme (HMAC-SHA256 over `"<t>." + body`, ±5 min) with a per-network secret. `INSERT … ON CONFLICT DO NOTHING`
   creates the conversion; otherwise the stored row is locked and moves only forward (pending → approved |
   rejected; decided is final), else `duplicate`. The click (from `subid`, found by primary key in one partition)
   gives the deal and the viewer. The deal's counter row gains the conversion on creation and the commission when
   approved — in the postback's own transaction, so a replay can never count twice. An unattributable postback is
   kept (click_id NULL): the network's numbers must still reconcile.
9. **One open application per card, enforced by the database.** `card_applications_one_open` is a partial unique
   index on (account, card) where status is submitted or in_review; the API answers its violation 409
   `APPLICATION_EXISTS`, so a race with different Idempotency-Keys has exactly one winner. The card must be
   published in commerce's catalog projection. The status history is an append-only jsonb array (the plan's
   `jsonb[]`); every change publishes `commerce.application.status-changed` (topic `dexter.commerce.application.v1`)
   in the same transaction. Operators decide on the `commerce_admin` pool, If-Match on the version; a rejection needs
   a reason.
10. **Ads: weighted rotation, then an atomic cap.** A serve draws among the placement's live (campaign, creative)
    pairs with probability ∝ campaign weight × creative weight (`AdRotation`, the roll injected); the chosen
    campaign's per-visitor counter is checked and incremented in one script (TTL = the campaign's window from the
    first impression), and the impression counter with it — so ten concurrent serves never pass a cap of three. A
    capped campaign leaves the draw and it is repeated; none left = `FREQUENCY_CAPPED`. Ad-free is billing's
    `adFree` entitlement, projected from `billing.entitlements.changed` (version-guarded), not the token's tier: it
    is fresher than any token.
11. **Three actors no token carries:** TRACKER (raw rows, counters, conversions — system work across accounts),
    PROJECTOR (the global card projection) and PUBLIC (anonymous reads). Operators are SUPPORT / CATALOG_EDITOR /
    SUPER_ADMIN on the admin pool; identity gains `commerce:read|write|decide` (SUPPORT reads and decides,
    CATALOG_EDITOR reads and writes).

## Consequences

- Deal administration (`/v1/admin/commerce/deals`) exists beside the campaign CRUD the plan names: deals have to come
  from somewhere, and a migration is no place for marketplace data.
- The gateway routes `/r/**` (public) and `/v1/admin/commerce/**` to commerce.
- A deal's affiliate URL never reaches a client; every outbound link is a signed `/r/` path.
