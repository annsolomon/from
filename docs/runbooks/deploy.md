# Deploy and rollback (P19)

The local stack (`scripts/run.sh`) and production run the same artifacts: one image per service
(`infra/docker/service.Dockerfile`: distroless Java 25, non-root, built from the service's Spring Boot jar and scanned
by `scripts/image-scan.sh` in every `verify.sh full`), configuration from the environment, secrets from Vault.

## What a release is
- Ten images `dexter/<svc>:<version>` (gateway, identity, ledger, catalog, portfolio, planning, reco, billing,
  commerce, audit) built from one commit, plus the ai-parser. Same version on all of them; compatibility across
  services is guaranteed by the event contracts (`contracts/`, versioned topics `dexter.<svc>.<agg>.v1`), not by
  deploying in lockstep.
- Database changes ship inside the service: Flyway runs at start as `<svc>_owner` (the app role cannot run DDL).

## Production shape (Kubernetes)
| Concern | How |
|---|---|
| Discovery | Kubernetes Services (DNS); the gateway's `GATEWAY_URI_<SVC>` point at them. No Consul (ADR-0026). |
| Secrets | Vault Agent injector, Kubernetes auth, one Vault role per service account; renders the same variables `scripts/lib/vault_tool.py env` renders locally. Identity gets a token for Transit (`VAULT_TOKEN_FILE` = the Agent sink). |
| Probes | `/actuator/health/liveness`, `/actuator/health/readiness` (enabled in every `application.yml`). |
| Management port | Set `MANAGEMENT_SERVER_PORT` (e.g. 9090) so `/actuator/**` (Prometheus) is not on the service port; only the gateway is exposed, and it routes `/v1/**` only. |
| Profiles | `SPRING_PROFILES_ACTIVE=prod` — never `dev` (the dev endpoints and labs exist only there; `ProdProfileIT`). |
| Tracing | `DEXTER_TRACING_ENABLED=true`, `DEXTER_TRACING_SAMPLING=0.1`, `DEXTER_OTLP_TRACES=http://otel-collector:4318/v1/traces`. |
| Resources | Requests 0.5 vCPU / 512 MiB, limit 2 vCPU / 1 GiB per instance to start; `-XX:MaxRAMPercentage=75` is in the image. Ledger and reco scale out first (docs/perf/results.md). |
| Kafka | Topics are created by the services at start (TopicRegistry: main + per-consumer retry/DLQ); prod partitions 12, replication 3. |

## Deploy (rolling)
1. `verify.sh full` green on the release commit (CI report on `verify-reports`) and `docs/release/checklist.md` done.
2. Check the migrations in the release: **expand only** (new tables, nullable columns, new indexes built
   `CONCURRENTLY` in their own migration, new functions). A destructive change (drop / rename / NOT NULL on existing
   data) ships in the release *after* the code that stopped needing the old shape (expand → migrate → contract).
3. Roll one service at a time, identity first, then the services, the gateway last:
   `kubectl set image deploy/<svc> <svc>=registry/dexter/<svc>:<version>`; `kubectl rollout status deploy/<svc>`.
   The first new pod runs the migrations; Flyway's lock keeps the others waiting.
4. Watch Grafana **Dexter · Services (RED)** for the service (5xx ratio, p99) and **Events** (outbox lag, consumer
   lag, dead letters) for 10 minutes before the next service. The alerts in `infra/prometheus/alerts.yml` are the
   stop signs.

## Rollback
1. `kubectl rollout undo deploy/<svc>` (or `set image` to the previous version). Pods of the old version start
   against the migrated schema — safe because every migration in a release is expand-only (step 2 above).
2. Never roll back a migration by hand-editing `flyway_schema_history`. If a migration itself is wrong, ship a new
   forward migration that repairs it.
3. Events written by the new version stay valid for the old one: a consumer ignores fields it does not know and
   the topic version (`.v1`) only changes with a new topic, consumed side by side.
4. If the outbox backed up during the incident, the relay drains it on its own (chaos test *kafka-down*); watch
   `dexter_outbox_pending` fall to 0.
5. Write down what happened (time line, alerts, the trace that showed it) and link it from the incident ticket.
