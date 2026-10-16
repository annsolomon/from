# test-ui — THROWAWAY HTML trail (MASTER_PLAN 11.2)

Testing aid only. **Deleted in P19.** No service or library may reference this folder
(`verify.sh static` → `static.trail-isolation`).

- Serve: `./scripts/run.sh trail` → http://localhost:8099
- **Mock** mode answers from `fixtures/*.json` in the browser. **Live** mode calls same-origin paths
  that nginx (`infra/nginx/trail.conf`) proxies to the host: `/api/*` → gateway, `/svc/<name>/*` → one service.
- One page per phase (`pNN-*.html`), linked from `index.html`.
- P00 ships the kit skeleton: mode toggle, `Trail.api()`, request log with timings.
- P03 completes the kit (`trail.js`): personas + tenant switcher (`mountTenantSwitcher`, Live login via
  `/api/v1/auth/login` from P04), the MASTER_PLAN 4.4 window (`Pager`, `mountPager` + debug panel), the
  Idempotency-Key tester (`mountIdempotencyTester`), the event viewer stub (`mountEvents`), and a mock router with
  keyset paging (`collection`, `mockList`, `idempotent`, `useSeed`/`loadSeed`) that answers like the services
  (LIMIT_INVALID, CURSOR_INVALID, CURSOR_FILTER_MISMATCH, IDEMPOTENCY_KEY_*, UNAUTHENTICATED).
- `fixtures/seed/*.json` are **generated** by `seed/` (`./scripts/seed.sh fixtures`); `FixturesInSyncTest` fails
  when they are stale. Never edit them by hand.
- `tests/pager.spec.js` (plain `node --test`, no npm install) runs the pure parts of `trail.js` against the
  fixtures: the 4.5 pagination contract on the mock router, the 4.4 window, and the mock idempotency engine.
  `verify.sh` runs it (`static.trail-pager`).
- P02 (`p02-libs.html`) is backed by the dev-only lab `/_dev/p02/*` in ledger (`libs/dexter-lab`, A-23): real
  Postgres database `lab`, Kafka and Redis user `lab`. It mints its own tokens (issuer `https://identity.dexter.lab`).
