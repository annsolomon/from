# DLQ replay runbook (P19)

Every consumer has its own retry and dead-letter topics (ADR-0006): `<topic>.<consumer>.retry-1m`,
`<topic>.<consumer>.retry-10m`, `<topic>.<consumer>.dlq`. A message lands on the DLQ after its first delivery and
both retries failed, or at once when it cannot be decoded (poison). The **DexterDeadLetters** alert fires on
`dexter_dlq_messages{consumer,topic} > 0` (end minus beginning offset, measured by the consumer every 30 s).

## 1. Find out why
```bash
# what is there (headers: dexter-error = the last failure, dexter-attempt, dexter-original-topic)
docker compose -f infra/compose/docker-compose.yml exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic <topic>.<consumer>.dlq --from-beginning \
  --property print.headers=true --property print.key=true --timeout-ms 5000
```
Find the trace: the envelope's `traceparent` → Tempo → the failing `consume <type>` span and its exception.

## 2. Fix the cause first
Replaying into the same bug only puts the messages back on the DLQ (after two more retries). Deploy the fix, or
— for a poison message that can never be processed — decide it is dropped and leave it out of the replay.

## 3. Replay
```bash
scripts/dlq-replay.sh <service> <topic> <consumer> --dry-run       # counts, moves nothing
scripts/dlq-replay.sh <service> <topic> <consumer>                 # all of it
scripts/dlq-replay.sh <service> <topic> <consumer> --key <key>     # one aggregate's messages (key = account id)
# e.g. scripts/dlq-replay.sh reco dexter.ledger.expense.v1 reco-ledger
```
The tool (`in.dexter.events.consumer.DlqReplay`, run from the service jar) reads the DLQ up to the end offsets it
saw at start, re-publishes each message to the consumer's **retry-1m** topic (due now, `dexter-attempt: 1`,
`dexter-replayed: n+1`; never to the main topic, which other consumers share), and only then deletes the replayed
records from the DLQ (`deleteRecords`), so the gauge falls.

- Safe to repeat: consumers deduplicate on the event id (inbox), and an event that failed was never recorded.
- `--key`: Kafka can only delete a prefix of a partition, so records kept back by the filter — and everything
  after them on their partition — stay on the DLQ; the alert keeps firing until they are replayed or expire.
- A replayed message that fails again goes through retry-10m and back to the DLQ with `dexter-replayed` set.

Proof: `ConsumerIT.aDeadLetterIsCountedThenReplayedOnceTheCauseIsFixed`, `anUnfilteredReplayEmptiesTheDlq`;
`verify.sh full` runs the script (dry run) from a real jar against the live Kafka (`full.obs-check`).
