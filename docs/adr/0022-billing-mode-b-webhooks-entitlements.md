# ADR-0022: Billing — a checkout that survives a crash, a payment decided once, and a tier that reaches the token

- Status: Accepted (P16), proved by CI run 42449818720 (git `9430059`): `verify.sh full` PASS 22/22 +
  `trail-check.sh` PASS. Proof: billing unit **12** + IT **52** (the four named tests in `WebhookIT`,
  `ModeBRecoveryIT`, `OneActiveSubscriptionIT`; the Idempotency and Pagination contracts; RLS on 8 tables),
  identity IT 67 with `TierIT`, api-smoke 529 PASS / 0 FAIL with 42 of them P16, trail-check `p16-*` 9/9. r1 found
  A-97 (a provider key not scoped by account) through `IdempotencyContract.keysAreTenantScoped`.
- Context: MASTER_PLAN P16 asks for plans, subscriptions, entitlements, payments behind a provider adapter with a mock
  provider (success, failure, delayed HMAC-signed webhooks), invoices with a mock GST split, replay-safe webhooks,
  Mode B idempotency for checkout, seven endpoints and two events. Named tests: a replayed webhook has a single
  effect; out-of-order webhooks (succeeded before created); Mode B crash recovery (kill after the provider call,
  before completion → the sweeper resolves it); only one active subscription can exist.

## Decisions

1. **The checkout is three atomic phases (Mode B, MASTER_PLAN 5.3).** Phase 1 is one transaction: reserve the key
   with a lease, record the subscription `incomplete` and the payment `pending`, set the recovery point
   `payment_created`. Phase 2 is the provider call, outside any transaction, idempotent on the same key. Phase 3 is
   one transaction: apply the outcome and store the response the key replays. A failure in phase 1 rolls the key
   back with the rows, so a retry is clean (IdempotencyContract case 4). Once phase 2 has started the key is **never
   abandoned** — not on a timeout, not on a crash — because whether money moved is unknown; it is finished by a
   client retry after the lease (which asks the provider again with the same key, safely) or by the sweeper (§3).
   The P02 engine already had `beginExternal` / `recordRecoveryPoint` / `completeExternal`; P16 is its first
   external-money user, and the sweeper the engine's own javadoc deferred to P16.
2. **One function applies an outcome, whoever learned it** (`PaymentOutcomes.apply`): the provider's synchronous
   answer, a webhook in any order and any number of times, and the sweeper's lookup. It holds the payment row lock,
   and its one rule is `PaymentStatus.canMoveTo`: **a payment is decided once** (`pending → succeeded | failed`).
   The first decision activates the subscription (its first period, `billing.subscription.changed`), writes the
   invoice, and refreshes the entitlements; any later report — a replay, a second event saying the same thing, or a
   "created" arriving after "succeeded" — records nothing but a provider id it did not know. That single rule is what
   makes both named webhook tests true by construction rather than by special cases.
3. **The sweeper reaches across accounts through an owner function** (`billing.claim_stale_checkouts`), claiming
   in-progress checkout keys whose lease ran out and re-leasing them in one `SKIP LOCKED` statement, so every instance
   may sweep and a client retry meanwhile gets 409. It then asks the provider **by Dexter's own reference** (the
   payment id) — never by charging again — and applies what it hears; a charge the provider never saw closes as
   failed `not_submitted` and the key replays a 402 `PAYMENT_NOT_COMPLETED`. The platform template gives
   `idempotency_keys` no owner policy and FORCE RLS applies to the owner, so billing adds `idempotency_keys_owner`
   in its own migration — reco's A-84 lesson (`claim_dirty` claimed nothing until it had one), applied before the
   first CI run rather than after it. The period-end job uses the same shape (`billing.claim_period_ends`).
4. **"Only one active subscription" is a partial unique index, and `incomplete` counts.** `subscriptions_one_live`
   covers incomplete, trialing, active and past due (A-92). Leaving `incomplete` out — the plan's literal set —
   would let two checkouts with different keys both pass the "is anything live?" check, both charge, and only then
   collide at activation with the money already taken. Counting the checkout in flight refuses the second one at
   insert, before any call to the provider; the repository maps the violation to 409 `SUBSCRIPTION_EXISTS`.
5. **Webhooks: the signature is the credential, the event id is the replay guard.** `Webhook-Signature:
   t=<unix>,v1=<HMAC-SHA256 of "t." + raw body>`, ±5 minutes, compared in constant time over the **raw bytes** (the
   controller takes `byte[]`, never a re-serialised object). The event names Dexter's payment, an owner function
   (`billing.payment_account`, the A-30 pre-auth shape) says whose it is, and the rest runs in that account's
   transaction: `webhook_events` is keyed `(provider, provider_event_id)`, so a replay conflicts and is answered
   `duplicate` with no effect — sequentially or ten at once. A signed event naming no payment is `200 ignored`, since a
   provider retries anything that is not 2xx.
6. **GST is mock tax logic, computed once.** The tax is `Gst.on(price)` — `libs/portfolio-calc`'s single
   definition of 18 % HALF_UP, the A-74/A-82 rule of one answer to one question — and split CGST 9 % + SGST 9 %
   (CGST takes an odd paisa) when the place of supply is the supplier's state (`KA`), IGST 18 % otherwise. Splitting
   the one figure keeps the total identical wherever the customer is; computing each half separately would not.
   Prices are before GST and the charge is price + GST, so the invoice total equals the payment amount, which phase
   3 asserts. Invoice numbers are `DX/<Indian FY>/<serial>` from one sequence. The state list and the rule are a
   mock; a real tax engine replaces both.
7. **Entitlements are computed on read and announced on change.** `GET /v1/entitlements/me` is the plan of the
   entitling subscription (trialing, active, past due — FREE otherwise) with unexpired overrides laid over it,
   cached 60 s per account in Redis (dexter-cache, `TenantCache`) and dropped after every committed change (Path A).
   `EntitlementService.refresh` runs inside every transaction that can change them and publishes
   `billing.entitlements.changed` only when the tier or the map differs from what `entitlement_state` says was last
   announced; that table's per-account `version` is the event's ordering, because a subscription's own version
   restarts at 0 for the next subscription.
8. **The tier reaches the token through identity's own projection, not a call.** Identity consumes
   `billing.entitlements.changed` (consumer `billing-tier`, inbox-deduped) into `accounts.tier_cache` with a new
   `tier_version` column, updated only `WHERE tier_version < :v`, so a late event through a retry topic cannot
   downgrade anyone. The next login or refresh puts it in the `tier` claim. The update leaves the profile's `version`
   alone, so a client's `If-Match` on `/v1/me` stays valid. A token already issued keeps its tier until it expires
   (≤ 10 min), as every claim does.
9. **The mock provider behaves like a provider where Mode B depends on it.** It charges once per key, answers
   lookups by reference, decides by test token (`pm_mock_success`, `_decline`, `_insufficient`, `_delayed`,
   `_delayed_decline`), and posts HMAC-signed `payment.created` then the outcome, later, on another thread, over
   HTTP to billing's own `/v1/webhooks/mock`. Tests switch delivery to memory (identity's mail-transport pattern) so
   they can deliver a webhook twice, out of order, concurrently, or never — the sweeper test delivers none, to prove
   the sweeper and not the callback finished the job.
10. **Statuses beyond the plan's list.** `incomplete` and `incomplete_expired` (Stripe's names) for a checkout
    waiting on, or failed at, its first payment; neither publishes an event — no other service has anything to do
    with a subscription that never started. Cancel is at period end only; the period-end job then moves a canceling
    subscription to `canceled`, any other to `past_due` (renewal is not built, §11), and a past-due one to `expired`
    after a 3-day grace. Entitlements follow: past due keeps the plan through the grace.

## What this phase does not do

11. **No renewal charging.** A period that ends without a cancel goes past due and then expires rather than being
    charged again; nothing is silently free. Renewal is an off-session charge per period — the same Mode B shape
    with a key derived from (subscription, period) — and belongs with a real provider adapter.
12. **No plan change, refund or proration.** A second checkout is refused while a subscription is live. A payment
    that succeeds for a checkout the sweeper had already closed (and another subscription is live) is invoiced and
    logged `PAYMENT_SUCCEEDED_ON_CLOSED_SUBSCRIPTION`; the refund is not built.
13. **Nothing writes entitlement overrides yet.** The table, its precedence and its expiry are built and tested; the
    writer (perks, ad holidays) is P17 or an admin console. An override that lapses on its own is seen after the
    cache's 60 s and announces nothing.
14. **The mock provider keeps its state in one JVM.** A second billing instance's sweeper would not find the first
    instance's charges and would close them as `not_submitted`. It is a dev and test double; a real provider is the
    shared state.
