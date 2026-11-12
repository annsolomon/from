// P19 scale proof (MASTER_PLAN 8.1, P19 step 4) on the large seed (100k accounts, 5M expenses), through the gateway.
// Scenarios run together, as production traffic would:
//   browse  - the client window over /v1/expenses: first 50, then 25, then 24 (the 50/25/24 pattern), plus the
//             daily totals and categories reads a dashboard makes; ramps to the 8.1 throughput target (SHAPES.full)
//   writes  - expense creates with an Idempotency-Key; a timeout/5xx/409 retries with the SAME key, 1 in 10 is
//             re-sent after success and must replay (Idempotent-Replayed: true, same body)
//   reco    - cached recommendation reads (GET /v1/recommendations: cache-aside on the account's state version)
//   sse     - SSE soak: VUs hold /v1/recommendations/stream open (ticket flow) for a minute at a time, for
//             SSE_MINUTES (30 in the scale workflow); the workflow checks dexter_sse_connections returns to 0
// Run: k6 run -e BASE_URL=http://localhost:8080 -e PROFILE=full -e SEED_BULK_ACCOUNTS=99998 perf/k6/p19-scale.js
// PROFILE=smoke shrinks everything to a 1-minute check of the script itself.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { CLASS, bulkEmail, get, idempotentPost, login } from './lib/base.js';
import { SHAPES, thresholds } from './thresholds.js';

const PROFILE = __ENV.PROFILE || 'smoke';
const FULL = PROFILE === 'full';
const BULK_ACCOUNTS = Number(__ENV.SEED_BULK_ACCOUNTS || 99998);
const PASSWORD = __ENV.SEED_BULK_PASSWORD || 'Seed-Bulk-2026!';
const SSE_MINUTES = Number(__ENV.SSE_MINUTES || (FULL ? 30 : 1));
const BASE = __ENV.BASE_URL || 'http://localhost:8080';

const sseOpened = new Counter('dexter_sse_streams_opened');
const sseHeld = new Trend('dexter_sse_held_ms', true);
const windowRows = new Trend('dexter_window_rows');

export const options = {
  scenarios: {
    browse: { ...(FULL ? SHAPES.full : { executor: 'constant-arrival-rate', rate: 20, timeUnit: '1s', duration: '1m',
      preAllocatedVUs: 20, maxVUs: 50 }), exec: 'browse' },
    writes: { executor: 'constant-arrival-rate', rate: FULL ? 100 : 5, timeUnit: '1s', duration: FULL ? '18m' : '1m',
      preAllocatedVUs: FULL ? 100 : 5, maxVUs: FULL ? 400 : 20, exec: 'writes' },
    reco: { executor: 'constant-arrival-rate', rate: FULL ? 150 : 5, timeUnit: '1s', duration: FULL ? '18m' : '1m',
      preAllocatedVUs: FULL ? 100 : 5, maxVUs: FULL ? 300 : 20, exec: 'reco' },
    sse: { executor: 'constant-vus', vus: FULL ? 200 : 5, duration: `${SSE_MINUTES}m`, exec: 'sse',
      gracefulStop: '90s' },
  },
  thresholds: thresholds(PROFILE, [CLASS.LIST, CLASS.READ, CLASS.WRITE, CLASS.RECO, CLASS.AUTH]),
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
};

// One token per VU per scenario (sign-in is Argon2-expensive and rate-limited by design).
const tokens = {};
function me(offset) {
  if (!tokens[offset]) {
    // spread VUs over the whole seed so the database sees 100k accounts, not a hot handful
    const n = 1 + ((__VU * 7919 + offset) % BULK_ACCOUNTS);
    tokens[offset] = login(bulkEmail(n), PASSWORD);
  }
  return tokens[offset];
}

function page(token, limit, cursor) {
  const q = `limit=${limit}` + (cursor ? `&cursor=${encodeURIComponent(cursor)}` : '');
  const res = get(`/v1/expenses?${q}`, { token, cls: CLASS.LIST, name: 'GET /v1/expenses (list)' });
  const ok = check(res, { 'list 200': r => r.status === 200 });
  return ok ? res.json() : null;
}

export function browse() {
  const token = me(0);
  if (!token) return;
  // the client window: 50, then 25, then 24 (MASTER_PLAN 4.4 / the P07 trail's 50/25/24 window)
  let body = page(token, 50, null);
  let rows = body ? body.data.length : 0;
  for (const limit of [25, 24]) {
    if (!body || !body.page.hasMore) break;
    body = page(token, limit, body.page.nextCursor);
    rows += body ? body.data.length : 0;
  }
  windowRows.add(rows);
  const today = new Date().toISOString().slice(0, 10);
  const monthAgo = new Date(Date.now() - 30 * 86400000).toISOString().slice(0, 10);
  check(get(`/v1/expenses/daily-totals?from=${monthAgo}&to=${today}`,
    { token, cls: CLASS.READ, name: 'GET /v1/expenses/daily-totals' }), { 'totals 200': r => r.status === 200 });
  check(get('/v1/categories', { token, cls: CLASS.READ, name: 'GET /v1/categories' }),
    { 'categories 200': r => r.status === 200 });
}

export function writes() {
  const token = me(1);
  if (!token) return;
  idempotentPost('/v1/expenses', {
    occurredAt: new Date().toISOString(), amountPaise: 10000 + Math.floor(Math.random() * 90000),
    merchant: 'k6 scale', categoryPath: 'food.cafe', paymentMethod: 'upi', note: 'p19 scale', tags: ['k6'],
  }, { token, retries: 2, replayCheck: Math.random() < 0.1, name: 'POST /v1/expenses' });
}

export function reco() {
  const token = me(2);
  if (!token) return;
  const res = get('/v1/recommendations', { token, cls: CLASS.RECO, name: 'GET /v1/recommendations' });
  check(res, { 'reco 200': r => r.status === 200 });
}

export function sse() {
  const token = me(3);
  if (!token) { sleep(5); return; }
  const t = http.post(`${BASE}/v1/recommendations/stream-ticket`, null,
    { headers: { Authorization: `Bearer ${token}` }, tags: { class: CLASS.WRITE, name: 'POST stream-ticket' } });
  if (!check(t, { 'ticket 200': r => r.status === 200 })) { sleep(5); return; }
  const started = Date.now();
  // k6 has no SSE client: a GET with a 60 s timeout holds the stream open and reads what arrived (heartbeats every
  // 25 s, the latest result on connect). A timeout is the expected end of a held stream, not an error.
  const res = http.get(`${BASE}/v1/recommendations/stream?ticket=${encodeURIComponent(t.json('ticket'))}`,
    { timeout: '60s', tags: { name: 'GET /v1/recommendations/stream', class: 'sse' },
      responseCallback: http.expectedStatuses(0, 200) });
  sseOpened.add(1);
  sseHeld.add(Date.now() - started);
  check(res, { 'stream held >= 50 s': () => Date.now() - started >= 50000 });
}
