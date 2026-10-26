# ADR-0017: Statement uploads, the ai-parser and S3 identities

- Status: Accepted (P12a; proven by CI run 40419721529, git 81d63ae). Proof: `StatementsIT`, `StatementContractsIT`,
  `PortfolioRlsIT`, `StatementDomainTest`, ai-parser pytest (72), api-smoke P12, trail-check P12.
- Context: MASTER_PLAN P12 asks for statement upload → parser (async via Kafka) → review → confirm into
  `card_transactions`, PAN redaction before parsing, three mock bank formats, an idempotent confirm by line hash,
  and S3 identities on the object store (ADR-0003 deferred them to P12). Statements are the most sensitive files
  Dexter stores.

## Decisions

1. **The browser uploads straight to the object store; the service never streams the file in.**
   `POST /v1/statements/uploads` (Idempotency-Key) returns a presigned PUT (15 min) to a *quarantine* key
   `quarantine/<account>/<upload>.pdf`. Nothing reads a quarantined object except the scan.
2. **Submit is Idempotency Mode B with a recovery point.** `:submit` reads the quarantined object (≤ 10 MB + 1 byte),
   scans it (`StatementScan`: non-empty, ≤ 10 MB, `%PDF-` magic at byte 0; SHA-256), refuses a second live upload of
   the same file for the card (`STATEMENT_ALREADY_UPLOADED`, also a partial unique index), writes a clean copy to
   `statements/<account>/<upload>.pdf` and records recovery point `scanned` (own transaction). Phase 2 — status
   `submitted`, the `portfolio.statement.submitted` event (outbox) and the stored 202 — is one transaction. A crash
   between them resumes from `scanned` after the lease (2 min) without scanning again; any failure before phase 2
   commits abandons the key so the same key can retry. An antivirus hook would sit in the scan (not in P12a).
3. **The parser gets a presigned GET, not credentials.** The submitted event carries a 6-hour presigned GET on the
   *internal* endpoint, the SHA-256 and the size. The parser fetches only from configured object-store origins,
   refuses redirects, caps the size and checks the hash (`FILE_CHANGED`). It holds no S3 keys and no database.
4. **The ai-parser is a separate Python process speaking only Kafka.** FastAPI exposes `/health` only; the worker
   consumes `dexter.portfolio.statement.v1` (group `ai-parser`, manual commits after the result is acknowledged,
   idempotent producer) and publishes `ai-parser.statement.parsed` v1 on `dexter.ai-parser.statement.v1`. The
   output event id is UUIDv5 of the input event id, so a redelivery is a duplicate portfolio's inbox drops.
   Unreadable files are *results* (`status failed` + code), never retries; only an unreachable object store or Kafka
   is retried. Portfolio's relay declares the parser's output topic (the parser has no relay; auto-create is off).
5. **PAN redaction runs on every extracted line before any extractor sees it.** 13–19 digits, grouped by single
   spaces or dashes, that pass Luhn → `XXXX XXXX XXXX <last4>`; windows of consecutive digit groups are tested, so a
   card number followed by another short number is still caught. Numbers failing Luhn (references, phones,
   amounts) stay. The count is reported (`redactions`); nothing downstream — result, event, logs, staged lines —
   can carry a full PAN.
6. **Extractors are deterministic rules behind one interface.** `Extractor.detect(lines)` + `parse(lines)`; three
   fictional layouts (`saffron-v1`, `indus-v1`, `deccan-v1`) with different date formats, credit markers and
   grouping. Amounts are parsed with `Decimal` (exact paise, Indian or western grouping). A real OCR/LLM extractor
   plugs in behind the same interface; scanned PDFs without a text layer fail as `NO_TEXT` until then.
7. **Parsed lines are staged, reviewed, then confirmed through the manual-record write path.** The consumer stages
   `parsed_statements` + `parsed_lines` (FORCE RLS like every portfolio table) after portfolio's own checks
   (`StatementLines.problem`: 1–2000 lines numbered 1..n, kinds matching directions, no future days). Each line gets
   `client_ref = stmt:<sha256(card | day | amount | direction | normalised description | occurrence)[:40 hex]>`; the
   occurrence counts identical earlier lines in the same statement, so two real identical purchases stay two, while
   the same statement confirmed twice — or the same lines in another file/layout — import once (the existing unique
   `card_transactions_client_ref`). `:confirm` (optional key, optional `excludeLines`) calls
   `TransactionService.write` for each pending line, so statement, rewards, savings, the `transaction.recorded`
   event and the auto-matcher behave exactly as for a manual record (`source statement`). A PDF whose last four
   digits are not the card's is refused at confirm (`STATEMENT_CARD_MISMATCH`), visible beforehand as
   `parsed.cardMatches`.
8. **Files are deleted after the decision.** The quarantined object after a scan (pass or fail), the clean copy after
   confirm or discard; deletion is best effort after commit (a leftover is logged). Retention of anything longer
   is P18 (privacy).
9. **The object store has S3 identities.** `infra/objectstore/render-s3.sh` renders one identity from
   `OBJECTSTORE_ACCESS_KEY` / `_SECRET_KEY` at every container start (like the Redis ACL); anonymous requests are
   refused (verify `infra-checks` asserts an anonymous PUT gets 403); the healthcheck uses `/healthz`. Volumes are 256 MB
   with room for 64 (`-volume.max`): each bucket reserves its own volumes, and 1 GB volumes left no room for a third
   bucket on a CI runner. SeaweedFS 4.22's
   default CORS answers preflights for any origin, which the trail's browser PUT needs (trail-check P12 asserts it).
   Ledger imports and catalog snapshots already signed every request (P08/P10), so they are unaffected.

## Consequences

- The calc engines (P12b) are not part of this ADR; they wait for the old repository's sources (A-68).
- One identity for all services in dev; per-service identities and bucket policies are P19 hardening.
- A statement can take up to the parser's queue latency to appear; the API is 202 + poll (no SSE here).
