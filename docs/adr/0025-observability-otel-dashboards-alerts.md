# ADR-0025: Observability — OpenTelemetry traces across Kafka, Prometheus metrics, provisioned dashboards and alerts

- Status: Proposed — P19 step 1; becomes Accepted with the CI run whose `full.obs-check` passes (BUILD_README §3).
- Context: MASTER_PLAN P19 step 1 asks for OTel across all services; Grafana dashboards for RED metrics per
  service, DB pool, Kafka lag, outbox lag and SSE connections; alerts on error rate, p99, outbox lag > 5 s and a
  non-empty DLQ. The obs compose profile (collector, Tempo, Prometheus, Grafana) exists since P00 but nothing sent
  to it, no dashboard was provisioned and no rule was loaded.

## Decisions

1. **Traces: Spring Boot's OpenTelemetry starter, through one library.** `libs/dexter-observability` brings
   `spring-boot-starter-opentelemetry` (Micrometer Tracing → OTel bridge, OTLP exporter) to all ten services.
   HTTP server and client spans (no Java agent). Export is `management.tracing.export.enabled =
   ${DEXTER_TRACING_ENABLED:false}`: `scripts/run.sh` turns it on for the live stack, the Maven ITs never export
   (verify.sh exports `.env` into them, so the switch is not in `.env`). Sampling `${DEXTER_TRACING_SAMPLING:1.0}`;
   prod sets 0.1 (the parent's decision is honoured downstream). Endpoint `${DEXTER_OTLP_TRACES}` → the collector →
   Tempo.
2. **A trace crosses the outbox and Kafka.** The event library gets a static seam, `EventTracing`: the outbox
   writer stores the current W3C `traceparent` in the envelope (the field existed since P02, always null), and
   every `EventConsumer` runs the handler inside `EventTracing.current().consume(...)`. dexter-observability installs
   `MicrometerEventTracing`: `traceparent()` formats the current span; `consume` extracts the envelope's
   traceparent with the Micrometer `Propagator` and opens a `CONSUMER` span `consume <type>` (tags: consumer group,
   event id, type) as its child. Without the library both are no-ops. Static rather than a bean because relays and
   consumers are built by hand in each service's configuration and in tests. The relay itself adds no span: the
   hop from the request to the consumer is what an operator follows, and the envelope carries it.
3. **Metrics: pulled, not pushed.** Prometheus scrapes `/actuator/prometheus` (as since P00); OTLP metric push is
   off. Every meter carries `service=<svc>` and `application=dexter`. Histograms for `http.server.requests` and
   `dexter.event.handle`, so p95/p99 come from buckets. The event library registers its meters on
   `Metrics.globalRegistry` (Spring Boot adds its registries there):
   `dexter_outbox_lag_millis{schema}` / `dexter_outbox_pending{schema}` (the relay leader, at most every 5 s, one
   indexed query on unpublished rows), `dexter_dlq_messages{consumer,topic}` (end − beginning offset of each DLQ,
   every 30 s, from the consumer's own poll thread), `dexter_event_handle_seconds{consumer,type,outcome}`, Kafka's
   client metrics (`KafkaClientMetrics`, lag per client). Reco adds `dexter_sse_connections`. Hikari and JVM meters
   are Spring Boot's.
4. **Dashboards and rules are files, checked statically.** `infra/grafana/dashboards/*.json` (Services (RED),
   Events, Resources) are provisioned read-only into a "Dexter" folder; `infra/prometheus/alerts.yml` holds three
   recording rules and eight alerts (service down, 5xx > 2 %, p99 > 200 ms, outbox lag > 5 s, DLQ > 0, consumer lag
   > 1000, pool waiters, heap > 90 %), each with a runbook in `docs/runbooks/`. `verify.sh static.obs-config` parses
   every dashboard, rejects a query on a metric the services do not export (a typo is an empty panel or an alert
   that never fires — nothing else would notice), requires a runbook file per alert, and runs `promtool check
   rules` / `check config` in the pinned Prometheus image.
5. **Proven live, on data the run produced.** `verify.sh full` starts the obs profile before the services and,
   after the API smoke and k6, `scripts/lib/obs_check.py` asserts: all ten targets up; rules loaded and healthy;
   the histogram, outbox-lag, DLQ, Kafka-client, Hikari and SSE series present for every service that owns them;
   events handled > 0; the recording rule has series; Tempo has spans from all ten services and a trace that
   starts at the gateway and contains a `consume <type>` span (≥ 3 services in one trace); the three dashboards
   loaded in Grafana. The DLQ replay tool (docs/runbooks/dlq-replay.md) runs from a real jar in the same step.
6. **DLQ replay is a tool, not a procedure.** `DlqReplay` re-publishes a consumer's dead letters to its own
   retry-1m topic (never the shared main topic), then `deleteRecords` up to what it replayed, so the alert clears.
   Repeating it is safe (inbox dedupe).

## Consequences

- One trace per user action across services and Kafka; the gateway's activity events (written straight to Kafka,
  not through an outbox) carry no traceparent yet.
- Tests: `ConsumerIT` (DLQ gauge, replay, idempotent re-replay, truncation), `TraceparentTest`, static obs check,
  live obs check.
