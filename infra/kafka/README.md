# Kafka (KRaft, single node, dev)

- Host clients: `localhost:9092`. Containers: `kafka:19092`.
- Auto topic creation is OFF. Topics are created from the topic registry in `dexter-events` (P02):
  `dexter.<service>.<aggregate>.v1`, 3 partitions in dev (12 in prod), plus `retry-1m`, `retry-10m`, `dlq`.
