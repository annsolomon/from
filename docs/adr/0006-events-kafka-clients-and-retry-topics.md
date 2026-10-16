# ADR-0006: Events on plain kafka-clients, per-consumer retry topics

**Status:** Accepted (P02a, 2026-09-27) · proof pending the P02 Codespaces run (`OutboxRelayIT`, `ConsumerIT`)

## Context
MASTER_PLAN 6 needs an outbox relay (leader lock, LISTEN/NOTIFY, SKIP LOCKED, idempotent producer),
inbox dedupe, and non-blocking retries `...retry-1m`, `...retry-10m`, `...dlq`. It does not say whose
retry topics they are, or which Kafka client library to use.

## Decision
1. **kafka-clients directly** (Boot-managed version), no Spring Kafka. The relay needs a producer and
   exact control of when rows are marked; the consumer needs pause/seek for delayed retry stages.
   Spring Kafka's `@RetryableTopic` names topics by delay value and would add a second retry model.
2. **Retry and DLQ topics belong to one consumer:** `<topic>.<consumer>.retry-1m`,
   `<topic>.<consumer>.retry-10m`, `<topic>.<consumer>.dlq`. A shared retry topic would replay one
   consumer's failures into every other consumer group.
3. Consumer groups: `<service>.<consumer>` for the main stage, `<service>.<consumer>.<stage>` per retry stage.
4. A retry stage pauses a partition until its head message's `dexter-not-before`; stage messages are
   FIFO with one delay, so the head is always the earliest due.
5. Invalid envelopes (poison) skip the retries and go straight to the DLQ.
6. Offsets are committed only after the handler succeeded or the forward was acknowledged.
7. The relay's DataSource is non-pooled: its session advisory lock must die with its connection.
8. `TopicRegistry` creates missing topics idempotently at startup (dev Kafka has no volume).

## Consequences
- Retries break per-key ordering for the failed event only; projections guard with
  `ProjectionVersionGuard` (`projected_version < :version`).
- Delivery is at-least-once end to end; effects are exactly-once through `InboxGuard` in the effect's
  transaction. `OutboxRelayIT` proves a kill mid-batch republishes (≥13 records for 10 events) and
  the consumer still applies 10 effects.
- Retry delays are configuration (`EventConsumer.Stage`); tests use 300ms/600ms with the same topic names.
