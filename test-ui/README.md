# test-ui — THROWAWAY HTML trail (MASTER_PLAN 11.2)

Testing aid only. **Deleted in P19.** No service or library may reference this folder
(`verify.sh static` → `static.trail-isolation`).

- Serve: `./scripts/run.sh trail` → http://localhost:8099
- **Mock** mode answers from `fixtures/*.json` in the browser. **Live** mode calls same-origin paths
  that nginx (`infra/nginx/trail.conf`) proxies to the host: `/api/*` → gateway, `/svc/<name>/*` → one service.
- One page per phase (`pNN-*.html`), linked from `index.html`.
- P00 ships the kit skeleton: mode toggle, `Trail.api()`, request log with timings.
  P03 adds the login/tenant switcher, pagination window + debug panel, idempotency tester and event viewer.
- P02 (`p02-libs.html`) is backed by the dev-only lab `/_dev/p02/*` in ledger (`libs/dexter-lab`, A-23): real
  Postgres database `lab`, Kafka and Redis user `lab`. It mints its own tokens (issuer `https://identity.dexter.lab`).
