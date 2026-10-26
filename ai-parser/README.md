# ai-parser (Python, FastAPI + aiokafka) — P12

Statement PDF → redacted, structured card transactions, asynchronously via Kafka (ADR-0017). The old
implementation (`ai-parser-service/` in the old repo) is reference only.

```
portfolio  --portfolio.statement.submitted (dexter.portfolio.statement.v1)-->  ai-parser
           <--ai-parser.statement.parsed   (dexter.ai-parser.statement.v1)---
```

- `dexter_parser/pdftext.py` — PDF text lines (pypdf); also a tiny deterministic PDF writer for the mock statements.
- `dexter_parser/redact.py` — Luhn-checked PAN redaction, run on every line before parsing.
- `dexter_parser/formats.py` — `Extractor` interface + three fictional layouts (`saffron-v1`, `indus-v1`, `deccan-v1`).
- `dexter_parser/parse.py` — pipeline + validation; failures are results (`status failed`, `errorCode`).
- `dexter_parser/fetch.py` — presigned GET from allowed object-store origins only, no redirects, size cap, SHA-256 check.
- `dexter_parser/worker.py` — the Kafka loop (manual commits after the result is acknowledged; output id = UUIDv5 of
  the input id, so redeliveries dedupe in portfolio's inbox).
- `dexter_parser/app.py` — FastAPI with `GET /health` only.
- `dexter_parser/samples.py` — mock statements + their expected parse (`python -m dexter_parser.samples OUTDIR`).

Run: `./scripts/run.sh services` starts it with the Java services (venv in `.run/venv-ai-parser`, port
`PORT_AI_PARSER` = 8090), or `./scripts/run.sh parser`. Tests: `./scripts/verify.sh unit` (step `unit.ai-parser`)
or `cd ai-parser && ../.run/venv-ai-parser/bin/python -m pytest`. The tests validate every event the parser emits
against `contracts/events/*.json`.

Environment: `KAFKA_BOOTSTRAP` (default `127.0.0.1:${KAFKA_PORT:-9092}`), `PARSER_OBJECTSTORE_ORIGINS`
(comma-separated; default `http://127.0.0.1:8333,http://localhost:8333`), `PARSER_MAX_BYTES` (10 MB),
`PARSER_KAFKA_ENABLED` (true).
