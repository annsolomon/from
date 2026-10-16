// Scenario TEMPLATE (copy to perf/k6/pNN-<service>.js in the phase that ships the endpoints; not run before P07).
// Shows the three load patterns MASTER_PLAN 19 asks for, built on lib/base.js + thresholds.js:
//   browse  - log in as a seed user and walk /v1/expenses with the 50/25 window;
//   writes  - POST /v1/expenses with an Idempotency-Key, retries reuse the key, a replay check on 1 in 10;
//   errors  - the cheap ProblemDetail paths (bad limit, tampered cursor) stay fast and coded.
// Run: k6 run -e BASE_URL=http://localhost:8080 -e PROFILE=smoke perf/k6/scenario.template.js
// Needs a loaded seed (./scripts/seed.sh medium) and the identity + ledger services (P04, P07).
import { sleep } from 'k6';
import { CLASS, bulkEmail, expectProblem, get, idempotentPost, login, walkList } from './lib/base.js';
import { SHAPES, thresholds } from './thresholds.js';

const PROFILE = __ENV.PROFILE || 'smoke';
const BULK_ACCOUNTS = Number(__ENV.SEED_BULK_ACCOUNTS || 9998); // medium seed
const PASSWORD = __ENV.SEED_BULK_PASSWORD || 'Seed-Bulk-2026!';

export const options = {
  scenarios: {
    browse: { ...(PROFILE === 'full' ? SHAPES.full : SHAPES.smoke), exec: 'browse' },
    writes: { executor: 'constant-vus', vus: PROFILE === 'full' ? 50 : 2, duration: PROFILE === 'full' ? '18m' : '30s', exec: 'writes' },
    errors: { executor: 'constant-vus', vus: 1, duration: '30s', exec: 'errors' },
  },
  thresholds: thresholds(PROFILE, [CLASS.LIST, CLASS.WRITE, CLASS.AUTH]),
};

// One token per VU (login is rate-limited and Argon2-expensive by design; don't log in per iteration).
let token = null;
function me() {
  if (!token) token = login(bulkEmail(1 + ((__VU - 1) % BULK_ACCOUNTS)), PASSWORD);
  return token;
}

export function browse() {
  walkList('/v1/expenses', { token: me(), maxPages: 4 });
  sleep(1);
}

export function writes() {
  idempotentPost('/v1/expenses', {
    occurredAt: new Date().toISOString(), amountPaise: 10000 + Math.floor(Math.random() * 90000),
    merchant: 'k6 load', categoryPath: 'general', paymentMethod: 'upi',
  }, { token: me(), replayCheck: __ITER % 10 === 0, name: 'POST /v1/expenses' });
  sleep(0.5);
}

export function errors() {
  expectProblem(get('/v1/expenses?limit=101', { token: me(), cls: CLASS.LIST, name: 'GET /v1/expenses (bad limit)' }), 400, 'LIMIT_INVALID');
  expectProblem(get('/v1/expenses?cursor=tampered.0000', { token: me(), cls: CLASS.LIST, name: 'GET /v1/expenses (bad cursor)' }), 400, 'CURSOR_INVALID');
  sleep(1);
}
