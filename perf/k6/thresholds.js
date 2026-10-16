// k6 thresholds template (MASTER_PLAN 8.1 targets). Scenarios tag requests with `class` (lib/base.js) and build
// their thresholds here, so every phase is measured against the same numbers:
//   import { thresholds } from './k6/thresholds.js';
//   export const options = { ..., thresholds: thresholds('smoke', ['list', 'write']) };
//
// Profiles:
//   smoke  - per phase (10 VUs, 30 s, MASTER_PLAN 10): loose latency, strict errors. Catches regressions, not
//            capacity. Dev boxes and CI runners are noisy, so p95 rather than p99.
//   full   - P19 scale proof on the large seed: the 8.1 table as written (p99), plus checks.

/** MASTER_PLAN 8.1, end-to-end through the gateway. */
export const TARGETS = {
  list: { p99: 150 },   // keyset list, 25–50 rows, 5M expenses / 100k accounts
  read: { p99: 150 },
  write: { p99: 200 },  // with idempotency + outbox
  reco: { p99: 100 },   // cached recommendation
  auth: { p99: 300 },   // Argon2id costs ~20-50 ms by design (7.1); not an 8.1 row, budget recorded here
  calc: { p99: 150 },
  health: { p99: 50 },
};

const SMOKE_P95 = { list: 200, read: 200, write: 250, reco: 150, auth: 400, calc: 200, health: 200 };

/**
 * @param profile 'smoke' | 'full'
 * @param classes request classes this scenario uses (keys of TARGETS)
 * @param extra   additional thresholds to merge (e.g. a custom metric)
 */
export function thresholds(profile = 'smoke', classes = ['health'], extra = {}) {
  const t = {
    http_req_failed: [profile === 'full' ? 'rate<0.001' : 'rate<0.01'],
    checks: ['rate>0.99'],
  };
  for (const c of classes) {
    if (!TARGETS[c]) throw new Error(`unknown request class ${c}`);
    t[`http_req_duration{class:${c}}`] = profile === 'full' ? [`p(99)<${TARGETS[c].p99}`] : [`p(95)<${SMOKE_P95[c]}`];
    t[`http_req_failed{class:${c}}`] = [profile === 'full' ? 'rate<0.001' : 'rate<0.01'];
  }
  return { ...t, ...extra };
}

/** Standard executor shapes. */
export const SHAPES = {
  smoke: { vus: 10, duration: '30s' },
  // P19: ramp to the 8.1 throughput target (≥ 1,500 req/s per ledger instance, mixed).
  full: {
    executor: 'ramping-arrival-rate', startRate: 100, timeUnit: '1s', preAllocatedVUs: 200, maxVUs: 1000,
    stages: [{ target: 500, duration: '2m' }, { target: 1500, duration: '5m' }, { target: 1500, duration: '10m' }, { target: 0, duration: '1m' }],
  },
};
