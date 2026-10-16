# contracts (Maven module `dexter-contracts`, since P02)

- `events/<type>.v<n>.json` — JSON Schema (2020-12 subset) of each event's `data`, plus `envelope.v1.json`.
- `src/main/java/in/dexter/contracts/events/<service>/<Action>V<n>.java` — the record producers and consumers share.
- `SchemaContractTest` fails when a record and its schema disagree (property names, required set,
  types) or when a sample instance does not validate. It also validates real envelopes from
  `dexter-events`' `EnvelopeCodec` against `envelope.v1.json`.

Rules: additive evolution only; breaking change → new `v<n+1>` schema + record, both produced for one release.
OpenAPI contracts join this module from P04.
