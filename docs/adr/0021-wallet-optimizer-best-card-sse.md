# ADR-0021: Reco B — a wallet valued as a wallet, a card chosen for one purchase, and a push that crosses instances

- Status: Accepted (P15). Proof: reco unit (`OptimizerTest` including a brute force over the whole candidate set,
  `BestCardTest`, `OffersTest`) + IT (`WalletIT`, `BestCardIT`, `StreamIT`, `ProjectionsIT`, `RecoRlsIT`),
  `RedisAclIT` for the channel namespace, api-smoke P15, trail-check `p15`.
- Context: MASTER_PLAN P15 asks for a wallet optimizer (dominance pruning, beam top-15, exhaustive 2- and 3-card
  combos, exclusive category assignment, caps per card per period, the marginal value of each next card),
  best-card-now over held cards with cap headroom, active offers and milestone proximity, and SSE with a one-time
  60-second ticket, Redis pub/sub fan-out on `reco:acct:{id}`, a 25-second heartbeat and `Last-Event-ID` replay.
  Named tests: optimizer goldens vs brute force, best-card honouring an exhausted cap, SSE across two instances,
  and a ticket usable once.

## Decisions

1. **A wallet is valued as a wallet, not as a sum of rankings.** Three things make it so, and all three fall out of
   valuing a card with `Savings.of` against `SpendVector.only` the roots routed to it — the same arithmetic
   `GET /v1/recommendations` uses, not a second copy (the A-82 rule). A rupee is spent once, so each root goes to
   exactly one card. A cap is per card, so the second card is often worth more than its solo score suggests. Fees
   and milestones follow the routed spend, so a three-card wallet is correctly less likely to clear each card's
   waiver — charging every card its solo waiver probability would have made big wallets look free.
2. **Routing is the hard part, and it is exact where it can be.** With per-card caps this is a generalized
   assignment problem, so `Optimizer.assign` brute-forces the roots across the wallet rather than deciding them one
   at a time — which root a card's cap is spent on changes what the whole wallet is worth. `MAX_CONTESTED` (6)
   bounds it; past that the routing is greedy and the wallet reports `exact: false`, because an approximate figure
   that admits it beats a wrong one that does not. Every card-against-a-set-of-roots valuation is memoized per
   call, which is what keeps the nesting affordable.
3. **"Dominance pruning" is reported, not acted on** — a deliberate retreat from the plan's wording, and the most
   useful thing this phase learned. Every attempt to make pairwise dominance *safe* was caught by the brute force,
   because every quantity that decides a wallet's value is per card and none are additive:
   - a **cap** is per card, so a second copy of a capped card brings a second cap. The first version pruned exactly
     the card that makes a two-card wallet worth having;
   - a **fee waiver** depends on how much spend the card is given, so a ₹1,000 fee that gets waived beats a ₹500
     fee that does not — and which happens depends on the routing. Comparing sticker fees dropped better cards;
   - a **milestone** is per card, so two cards each reaching their own beats one card reaching one. A card that
     brings a milestone is never redundant however well another card earns.
   Each fix exposed the next. A condition strong enough to be sound would have to evaluate the card in every wallet
   it might join, which is the search itself. So `Result.dropped` is an explanation ("you would not hold both of
   these") and the search stays exhaustive over the beam — the beam being the one approximation, which is what the
   plan names anyway. `OptimizerTest` compares against a brute force at every size, and that comparison is only
   meaningful because nothing is pruned out from under it.
4. **Spend counts towards a card's waiver even where it earns nothing.** An earlier `assign` routed a root only to
   cards that earned on it, and the brute force found better wallets than the optimizer did — the search was fine,
   the model was wrong. Putting the groceries on a card that pays nothing on groceries can still be what waives its
   fee, so every root is a candidate for every card in the wallet.
5. **Nothing in P15 is cached.** The P14 ranking is cached because it is the same question every time and wanted on
   every screen. A wallet is asked for deliberately and takes milliseconds over a beam of fifteen; best-card-now's
   answer depends on the amount, so there is nothing to cache; and a stream is a notification. A second cache keyed
   by a second hash would be one more thing to keep coherent for no gain.
6. **best-card-now needs this month on this card, which the P14 profile cannot answer.** P14 built monthly averages
   per category root — the right input for "which card should I get", the wrong one for "which card should I put
   this on", where the question is how much of a cap is left *now*. So `card_period_spend` holds per-card,
   per-month, per-root spend as exact deltas (A-86). The source is the ledger event reco already consumes:
   `ExpenseStateV1` has carried `userCardId` since P07, so there is no new event source and nothing to keep in
   step. An expense paid by UPI or cash has no card and moves no headroom.
7. **What a rule has earned this month is computed, not stored.** Storing earned reward would freeze the rules as
   they were when the expense arrived, so a republished card would be scored against stale terms. Instead
   `Savings.monthlyEarnedByRule` replays the card's current rules over the stored spend — and it reads
   `Savings.of`'s own lines rather than walking the rules again, so the headroom it reports and the reward it
   reports cannot disagree.
8. **best-card-now uses the purchase's exact category path, so A-81's root approximation does not apply.** At a
   till the category is known exactly, so a rule on `food.dining` earns on the whole amount of a
   `food.dining.coffee` purchase. A rule written for the merchant beats a category rule even at a lower rate. Only
   a purchase that actually crosses a milestone is credited with it — crediting proximity would flatter every card
   with a far-off milestone.
9. **The ticket is the credential for the stream, because a browser's `EventSource` cannot set a header.** So the
   gateway lists `GET /v1/recommendations/stream` as public and this service checks the ticket: 60 seconds, one
   use, consumed atomically by a Lua script (`GETDEL` is not in the per-service ACL's command list; `get` and
   `unlink` are), naming one account and nothing else.
10. **A push crosses instances over Redis pub/sub.** A browser is attached to one instance and the recompute that
    concerns it may happen on another — the sweeper runs everywhere. So a changed recompute publishes on
    `reco:acct:{id}` and every instance relays it to the streams it holds (A-87). This is the first use of Redis
    pub/sub in the system, and the per-service ACL had to gain the service's own `<svc>:*` channel namespace plus
    the pattern pub/sub commands — Spring's listener container takes one pattern subscription rather than one per
    account, since an instance's set of streams changes constantly.

    An instance subscribes to `reco:*`, not the narrower `reco:acct:*` that would read better, because **Redis
    compares a `PSUBSCRIBE` pattern to an ACL's channel patterns by literal equality rather than by glob**: with
    `&reco:*` granted, `PSUBSCRIBE reco:acct:*` is refused outright. So the subscription uses exactly the granted
    pattern and `onMessage` filters by prefix, which costs nothing — the only channels reco publishes on are
    `reco:acct:<id>`, and tickets are keys, not channels. `RedisAclIT` pins all of it: a service may publish and
    pattern-subscribe on its own namespace, a narrower pattern is not covered by the granted one, and another
    service's channels stay out of reach.
11. **The push happens after the commit, through a one-method interface.** `RecommendationService.ResultListener`
    is called once the write transaction has returned: a listener that throws must not roll back a stored result,
    and a listener that re-reads must not see the row as it was. It also keeps the recompute testable with no
    Redis, which is how `DebounceIT` and `RecommendationsIT` exercise it.

    The stream wiring is *not* conditional on Redis being present, and the first attempt to make it so is the
    lesson: `@ConditionalOnBean(StringRedisTemplate.class)` on an ordinary `@Configuration` is evaluated while
    user configuration is scanned, before auto-configuration has run, so the template Boot was about to create did
    not exist yet — the condition failed and `StreamController` came up with nothing to inject. That annotation is
    only dependable inside an `@AutoConfiguration` class. Reco declares the Redis starter and configures it, so
    the template is always there, and Lettuce connects lazily: a Redis that is down costs the stream, not the
    service.
12. **Delivery is best-effort, and the stored result is the system of record.** A missed push is corrected by the
    next one, by the 25-second heartbeat failing, or by the reconnect replay: `Last-Event-ID` is the state hash the
    client last saw, so a reconnect that already has the latest result is told `recommendation.unchanged` rather
    than being quietly left to wonder.
13. **A rule's cap is one cap, not one per category root (A-85).** Found while building the optimizer, which has to
    account caps per card: `Savings.of` was applying a rule's monthly cap once *per root*, so a catch-all rule with
    a ₹500 ceiling — a very common Indian card shape — earned ₹500 for every category it covered. The cap is now
    carried across the roots that rule earns on, biggest root first, and every line reports the headroom it had so
    a figure below the cap is readable. `StateHash.ENGINE_VERSION` went to 2, which is exactly what that rule is
    for: every stored result recomputes rather than being served as fresh against the old arithmetic.

14. **A rule written for a merchant earned nothing at all, anywhere (A-89).** `Rules.rewardPaise` asked
    `RewardTerms` what a rule earns while passing no merchant, so a merchant rule matched nothing and returned
    zero — in `GET /v1/recommendations` as much as in best-card-now. ADR-0020 said a merchant rule was "scored as
    the category rule it sits under"; it was in fact scored as zero, and no test caught it because every P14
    fixture used category rules. It now earns at its own rate, and `ENGINE_VERSION` went to 3.

    The near miss is worth recording too. The unit test I wrote for this used `"m-1"` as a merchant id, and
    `Rule.shared()` treats an unparseable merchant id as "no merchant" — so the test passed whether or not the
    merchant reached the arithmetic, and only the integration test, with real UUIDs, failed. The test now uses
    UUIDs, and a separate one pins the malformed-id behaviour: a merchant-specific rule that names no real
    merchant earns nothing, which is the conservative reading and the right one for money.

## What this phase does not do

- **No wallet cache and no wallet history.** §5. If the wallet becomes a screen people land on rather than a thing
  they ask for, it gets the same treatment as the ranking: a state hash and a stored row.
- **No offer stacking rules beyond the catalog's own flag.** At most one non-stackable offer plus every stackable
  one, which is what `stackable` means. Issuer-specific stacking rules are a catalog question.
- **No merchant dimension in the spend profile.** Unchanged from ADR-0020 in intent: best-card-now matches
  merchant rules because it is asked about one merchant, while the wallet and the ranking score a merchant rule as
  the category rule it sits under — which is now actually what happens, rather than scoring it as zero (§14).
- **The SSE stream carries the result, not a diff.** A payload is small and a diff would need the client's last
  state; `Last-Event-ID` tells us only what it saw, not what it still holds.
- **Nothing seeded reaches reco's projections** (inherited, BUILD_README §7): a seeded demo gives the optimizer one
  rankable card and no held cards until a run publishes them, which is why the trail page's Mock mode replays
  fixtures printed from the real engine.
