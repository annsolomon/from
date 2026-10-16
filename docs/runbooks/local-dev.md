# Runbook: local development (Codespaces)

```bash
cp .env.example .env             # post-create does this once
./scripts/run.sh core            # postgres, redis, kafka, objectstore (SeaweedFS S3 :8333), mailpit
./scripts/run.sh services        # builds jars if missing, starts all 10 services, waits for UP
./scripts/run.sh status
./scripts/run.sh trail           # http://localhost:8099
./scripts/run.sh obs             # grafana :3000, prometheus :9090, tempo :3200, otlp :4317/4318
./scripts/run.sh stop            # stop services
./scripts/run.sh down            # stop containers (keep data)
./scripts/run.sh nuke            # stop containers + delete volumes
```

**Postgres roles are created only on the first init of the `pgdata` volume.** If you change `DEXTER_DB_SERVICES`
or any `PG_PW_*` value, run `./scripts/run.sh nuke` and then `./scripts/run.sh core` again.

To inspect the rendered role SQL without Docker:
`set -a; . ./.env; set +a; bash infra/postgres/render-roles.sh | less`.
