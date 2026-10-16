# perf/ — k6 load tests (MASTER_PLAN 10, 8.1, P19)

| File | Role |
|---|---|
| `smoke.js` | Per-phase smoke (10 VUs, 30 s). `verify.sh full` runs it (`full.k6-smoke`). Add read paths to `SCENARIO_PATHS`. |
| `k6/lib/base.js` | Shared client. It sends `X-Request-Id` and a `class` tag, can log in a seed user, walks lists with the 50/25 window (`walkList`), and retries writes with the **same** `Idempotency-Key` (`idempotentPost`). |
| `k6/thresholds.js` | MASTER_PLAN 8.1 targets per request class. `thresholds('smoke'|'full', classes)`, plus executor `SHAPES`. |
| `k6/scenario.template.js` | Copy per phase (`k6/pNN-<svc>.js`): browse, idempotent writes and coded errors on a seed user. |

Seed users for load (`./scripts/seed.sh medium|large`):

- `u0000001@seed.test` … `u0009998@seed.test` (medium), all with `SEED_BULK_PASSWORD`.
- `asha@demo.in` and `ravi@demo.in` with `DEMO_PASSWORD_*`.
