# ADR-0015: Catalog ingestion — SSRF-safe collector, field-level review, the promoter as the only writer

- Status: Accepted (P10; proven by CI run 39109141399, git c68837d).
  Proof: `IngestionPipelineIT`, `IngestionCollectorIT`, `IngestionApiIT`, `CatalogRlsIT` (ingestion tables),
  `FetchPolicyTest`, `CardExtractorTest`, `CardDiffTest`, `SchedulesTest`, api-smoke P10, trail-check P10.
- Context: MASTER_PLAN P10 asks for collector → review → promoter: sources fetched by an SSRF-safe fetcher with hash
  diffing, resumable checkpoints and per-host politeness; a field-level diff against the published version in an
  admin-only keyset queue; approve creates the next version through the P09 publish path; the promoter is the only
  writer of published data and is idempotent.

## Decisions

1. **SSRF policy in two halves.** `FetchPolicy` (pure) checks the URL before any network access: https only, no
   credentials, no IP literals, the host on the allowlist (an entry also allows its subdomains) on the default
   port. `SafeFetcher` then resolves the host and refuses when *any* address is not public (loopback, RFC 1918,
   link-local incl. 169.254.169.254, CGNAT, ULA, multicast, reserved and documentation ranges, and IPv4 embedded in
   IPv6: mapped, compatible, NAT64, 6to4). Redirects are never followed by the HTTP client: each hop is checked
   again (at most 3). Bodies are capped (5 MB, refused not truncated), time is capped (10 s overall), the content
   type must fit the source kind (no SVG as an image). Java connects through the same JVM address cache the check
   filled, so a DNS answer cannot change between check and connect while the entry lives. Exact `host:port` *dev
   hosts* may use http and a private address (a local mock issuer site); production configures none.
2. **Sources save only fetchable URLs.** A source URL passes the same policy at create/patch (400 with the refusal
   code), so editors learn at once; the collector checks again on every fetch (the allowlist or DNS may change).
3. **Collector = crawl runs with a checkpoint per source.** A run lists source ids; after each source the
   checkpoint (`done`, stats, errors) is saved, so a run re-claimed after its worker died (claim older than 10 min)
   continues at the next source; after 3 attempts it fails `RUN_ABANDONED`. Claims use `FOR UPDATE SKIP LOCKED`, so
   several instances share work. Politeness: one fetch per host per `politeness` interval (2 s; 200 ms dev).
   Scheduling is a five-field cron in IST, at most hourly; the scheduler queues due sources.
4. **Change detection is content-addressed.** SHA-256 of the body; the same hash as the latest snapshot of
   (source, URL) ends the work ("unchanged": no snapshot, no extraction, no item). New content goes to the object
   store under `snapshots/<source>/<sha256>` (S3 API, `libs/dexter-objectstore`, SigV4 moved from ledger) and gets a
   snapshot row; HTML is extracted (`pattern-v1`: fees, waiver spend, APR, forex, fuel waiver, cashback per
   category, with a confidence per field); PDFs and images are stored for review by eye only.
5. **Field-level diff, absent ≠ removed.** Only fields the page states are compared with the card's current
   version; rule changes are keyed by category (`rules/base`, `rules/<path>`), merchant rules are never touched and
   caps/exclusions of a changed rule are kept. An empty diff makes no item. One pending item per card: a newer diff
   supersedes it, an identical diff is a duplicate. Sitemap pages are matched to the issuer's card by URL slug or
   normalised name; unmatched pages are kept as extractions.
6. **The promoter is the only writer of published data.** `Promoter.publish` (versions, rules, milestone, the
   public face, `catalog.card.published`) serves both the editor's publish (P09) and an approve. Approve locks the
   review item: a second approve — sequential or concurrent — answers the recorded promotion (`replayed: true`,
   unique `promotions.review_item_id`) and creates no version. Approve refuses a stale item (the card moved past
   its base version) and a card with unpublished draft edits (they would undo the promotion at their own publish);
   `DELETE …/draft` discards those edits.
7. **RLS: an INGESTOR actor role.** The worker runs on the catalog_admin pool with actor role `INGESTOR`, a role no
   token can carry: it writes runs, snapshots, extractions and review items, reads the *published* catalog only,
   cannot write published data and cannot record a promotion. Editors (CATALOG_EDITOR / SUPER_ADMIN) see
   everything; catalog_app has no grant on ingestion tables.

## Consequences

- Real issuer pages vary: `pattern-v1` reads common Indian issuer wording and never invents a field; a better
  extractor (PDF text, LLM-assisted) only has to produce the same extraction shape.
- The allowlist lives in configuration (`CATALOG_INGEST_ALLOWED_HOSTS`); adding an issuer host is a deploy-time
  change by design.
- Reco (P14/P15) and portfolio (P11) see promoted versions as ordinary `catalog.card.published` events.
