// k6 base library (MASTER_PLAN 10 load layer, 4.4 client window, 5 idempotency). Every phase's scenario imports
// this instead of hand-rolling requests, so load tests behave like the real clients:
//   * every request carries X-Request-Id (and a `class` tag that the thresholds in ../thresholds.js key on);
//   * lists are walked with the 50/25 window pattern and signed cursors, never offsets;
//   * writes carry an Idempotency-Key and retries reuse it (a retry must replay, not duplicate).
// k6 runs ES modules in its own JS runtime: only k6/* modules, no npm.
import http from 'k6/http';
import { check } from 'k6';
import crypto from 'k6/crypto';

export const BASE = __ENV.BASE_URL || 'http://localhost:8080';

/** Route classes (MASTER_PLAN 7.3 rate-limit classes + 8.1 targets). */
export const CLASS = { READ: 'read', LIST: 'list', WRITE: 'write', AUTH: 'auth', CALC: 'calc', RECO: 'reco', HEALTH: 'health' };

/** RFC 4122 v4 from k6's CSPRNG (k6 has no uuid module). */
export function uuidv4() {
  const b = new Uint8Array(crypto.randomBytes(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = Array.from(b, x => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

let seq = 0;
export function requestId() {
  seq += 1;
  return `k6-${__VU}-${__ITER}-${seq}`;
}

/** Request params: X-Request-Id, optional bearer token, JSON content type for bodies, a `class` tag. */
export function params({ token = null, cls = CLASS.READ, headers = {}, name = null, json = false } = {}) {
  const h = { 'Accept': 'application/json', 'X-Request-Id': requestId(), ...headers };
  if (token) h.Authorization = `Bearer ${token}`;
  if (json) h['Content-Type'] = 'application/json';
  const tags = { class: cls };
  if (name) tags.name = name; // groups URLs with ids/cursors into one metric series
  return { headers: h, tags };
}

export function get(path, opts = {}) {
  return http.get(`${BASE}${path}`, params(opts));
}

/**
 * Logs a demo/seed user in (identity, P04) and returns the access token, or null.
 * Seed users: asha@demo.in / ravi@demo.in (DEMO_PASSWORD_*), u0000001@seed.test.. (SEED_BULK_PASSWORD).
 */
export function login(email, password) {
  const res = http.post(`${BASE}/v1/auth/login`, JSON.stringify({ email, password }),
    params({ cls: CLASS.AUTH, json: true, name: 'POST /v1/auth/login' }));
  check(res, { 'login 200': r => r.status === 200 });
  return res.status === 200 ? res.json('accessToken') : null;
}

/** Bulk seed account n (1-based) - the medium seed has 9,998 of them, all with SEED_BULK_PASSWORD. */
export function bulkEmail(n) {
  return `u${String(n).padStart(7, '0')}@seed.test`;
}

/**
 * Walks a keyset list the way the client window does (MASTER_PLAN 4.4): first page `first` (50), then `page` (25)
 * until hasMore is false or maxPages is reached. Checks the page envelope and that no id repeats.
 * Returns {rows, pages, ok}.
 */
export function walkList(path, { token = null, first = 50, page = 25, maxPages = 4, query = {} } = {}) {
  const seen = new Set();
  let cursor = null;
  let pages = 0;
  let rows = 0;
  let ok = true;
  const name = `GET ${path} (list)`;
  do {
    const q = Object.entries({ ...query, limit: String(pages === 0 ? first : page), ...(cursor ? { cursor } : {}) })
      .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&');
    const res = get(`${path}?${q}`, { token, cls: CLASS.LIST, name });
    const body = res.status === 200 ? res.json() : null;
    const good = check(res, {
      'list 200': r => r.status === 200,
      'list envelope': () => body && Array.isArray(body.data) && body.page && typeof body.page.hasMore === 'boolean',
    });
    if (!good) { ok = false; break; }
    for (const r of body.data) {
      if (seen.has(r.id)) ok = false;
      seen.add(r.id);
    }
    rows += body.data.length;
    cursor = body.page.nextCursor;
    pages += 1;
    if (!body.page.hasMore) break;
  } while (pages < maxPages);
  check(null, { 'list: no duplicate ids across pages': () => ok });
  return { rows, pages, ok };
}

/**
 * POST with an Idempotency-Key; on a 5xx / 409 IDEMPOTENCY_IN_PROGRESS / network error it retries with the SAME key
 * (MASTER_PLAN 5.1). Returns the last response; checks that a retry after success is a replay.
 */
export function idempotentPost(path, body, { token = null, retries = 2, replayCheck = false, name = null } = {}) {
  const key = uuidv4();
  const payload = JSON.stringify(body);
  const p = () => params({ token, cls: CLASS.WRITE, json: true, headers: { 'Idempotency-Key': key }, name: name || `POST ${path}` });
  let res = http.post(`${BASE}${path}`, payload, p());
  for (let i = 0; i < retries && (res.status === 0 || res.status >= 500 || res.status === 409); i++) {
    res = http.post(`${BASE}${path}`, payload, p());
  }
  check(res, { 'write 2xx': r => r.status >= 200 && r.status < 300 });
  if (replayCheck && res.status >= 200 && res.status < 300) {
    const again = http.post(`${BASE}${path}`, payload, p());
    check(again, {
      'retry replays (Idempotent-Replayed)': r => r.headers['Idempotent-Replayed'] === 'true',
      'retry returns the same body': r => r.body === res.body,
    });
  }
  return res;
}

/** RFC 9457 ProblemDetail check: status + stable code. */
export function expectProblem(res, status, code) {
  return check(res, {
    [`problem ${status} ${code}`]: r => r.status === status && r.json('code') === code,
  });
}
