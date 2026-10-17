# ADR-0014: Catalog — drafts, immutable published versions, public reads without a tenant

- Status: Accepted (P09; proven by CI run 39046289166, git 1135b29). Proof: `CatalogAdminIT`, `CatalogPublicIT`, `CatalogRlsIT`, `CatalogPlanIT`,
  `CatalogProjectionIT`, `CardDocumentsTest`, `PlatformMigrationTest`, api-smoke P09 block, trail-check P09.
- Context: MASTER_PLAN P09 asks for a normalised, versioned card catalog: global data that the public reads without
  an account, editors change through drafts, and a publish that produces an immutable version, closes the previous
  validity range and emits a snapshot event used for projections (reco, portfolio).

## Decisions

1. **Three layers of a card.** `card_products` holds the *published face* (the columns filters and sorts use) and the
   status (`draft` → `published` → `retired`); `card_product_drafts` holds the editor's working document (JSON,
   versioned for If-Match); `card_product_versions` holds the frozen snapshots. The public never sees a draft: a new
   card is only a draft until its first publish, and edits to a published card stay in the draft until the next one
   (A-52).
2. **Publish = one transaction.** Lock the card; validate the draft (`CardDocuments`); if its canonical hash equals the
   published `content_hash`, answer the current version (`changed: false`, idempotent); otherwise close the open
   version and the open rules at `now`, insert version n+1 with the snapshot, insert the rules of n+1 valid from the
   same `now`, replace the milestone, copy the face columns, and write `catalog.card.published`. The reward rules'
   exclusion constraint (card, category-or-'', merchant-or-nil, `valid &&`) makes an overlap impossible even for a
   bug or a manual insert; a trigger makes versions immutable (only an open `effective_to` may be closed, no delete —
   not even by the owner).
3. **Snapshot = the API = the event.** The published snapshot keeps the seed's shape (plus `merchantId` on rules).
   The list and the detail return the current snapshot plus `version` (the detail's ETag); the event carries the
   same snapshot on the compacted topic `dexter.catalog.card.v1` (key = card id), so the latest event per card is
   a complete projection (`CatalogProjectionIT` rebuilds it byte for byte). Retire writes `catalog.card.retired` with
   the last snapshot; the envelope's aggregateVersion is the card row's version (monotonic across publish/retire).
4. **Public reads without a tenant.** The public endpoints run on the catalog_app pool with a fixed `PUBLIC` tenant
   (the context is set but no policy uses it): RLS shows published cards, their versions/rules/milestones and active
   offers, and nothing is writable. Admin endpoints run on a second pool as catalog_admin (identity's pattern,
   ADR-0010); every policy there requires the actor role CATALOG_EDITOR or SUPER_ADMIN, which comes from the verified
   admin token, on top of the `catalog:read/write/publish` permissions the interceptor checks.
5. **Category earning.** "Earns ≥ X bps on C" and `categories/potential` use one rule: the most specific rule of the
   card's current version that covers C (an ancestor-or-self path, or the base rule) and does not exclude it; merchant
   rules are not category rates. The list filter is written with NOT EXISTS (keyset base SQL carries no ORDER BY).
6. **Search** is trigram on `lower(name)` (GIN, partial on published), substring or `%` at 0.3, most similar first;
   not paged (top 20). It runs through `catalog.search_published_cards(q, limit)`, a SECURITY DEFINER function owned
   by catalog_owner: under catalog_app's policy the planner may not use an index for LIKE or pg_trgm's `%` (not
   leakproof, so they cannot run before the policy qual) and scans the table. The function states `status =
   'published'` itself, returns only public columns, and plans each call with its real pattern (dynamic SQL).
   `CatalogPlanIT` proves the inner plan with auto_explain (no seq scan on 50k cards).

## Consequences

- P10's ingestion pipeline writes drafts (the review queue approves → publish); it reuses `CatalogAdminService`.
- Reco (P14/P15) and portfolio (P11) build their card projections from `dexter.catalog.card.v1`.
- Offers have no edit endpoint yet (create / withdraw); P10/P17 extend them.
