// Trail kit (MASTER_PLAN 11.2) - THROWAWAY, deleted in P19.
//
// P00: Mock/Live toggle, Trail.api(), request log with timings.
// P01: mock handlers may return Trail.reply(status, body, headers); api() accepts {raw} bodies.
// P06: Live calls go through the gateway (/api → nginx → :8080); liveRoutes is empty (it was the P04/P05 identity bridge).
// P03: the complete kit -
//   * personas + tenant switcher (Asha / Ravi / Admin from the seed fixtures); Live mode logs in through
//     /api/v1/auth/login (identity, P04) and sends the bearer token; Mock mode scopes data to the persona (RLS).
//   * a fixture-backed mock router with keyset paging that behaves like dexter-db's KeysetPager: limit 1..100
//     (default 25, LIMIT_INVALID), signed opaque cursors (CURSOR_INVALID), filter binding
//     (CURSOR_FILTER_MISMATCH), limit+1 lookahead, (sortKey, id) DESC total order.
//   * the pagination window of MASTER_PLAN 4.4 (FIRST=50, PAGE=25, TRIGGER_OFFSET=1) + debug panel.
//   * an idempotency tester (and a mock Idempotency-Key engine: replay, 422, 400, in-flight join).
//   * an event viewer (mock outbox; Live polls a dev endpoint when a phase provides one).
//
// The pure parts (cursor, router, pager, idempotency, events) touch no DOM, so test-ui/tests/pager.spec.js runs
// them in Node. Only the mount*() functions use the document.
(function (global) {
  'use strict';
  const MODE_KEY = 'dexter.trail.mode';
  const PERSONA_KEY = 'dexter.trail.persona';
  const mocks = [];
  const logListeners = [];
  // Reading localStorage can throw (privacy mode, Node without --localstorage-file), so probe it once.
  const store = (() => { try { return typeof document !== 'undefined' ? global.localStorage : undefined; } catch { return undefined; } })();
  const safeGet = (k, d) => { try { return (store && store.getItem(k)) || d; } catch { return d; } };
  const safeSet = (k, v) => { try { if (store) store.setItem(k, v); } catch { /* ignore */ } };

  let mode = safeGet(MODE_KEY, 'mock');
  let seq = 0;
  const settings = { latencyMs: [40, 120] }; // simulated mock latency range; tests set [0, 0]

  function newRequestId() { return 'trail-' + Date.now().toString(36) + '-' + (++seq); }

  // ------------------------------------------------------------------ small utilities
  function b64urlEncode(text) {
    const bytes = new TextEncoder().encode(text);
    let bin = '';
    for (const b of bytes) bin += String.fromCharCode(b);
    return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }
  function b64urlDecode(s) {
    if (!/^[A-Za-z0-9_-]*$/.test(s)) throw new Error('not base64url');
    const bin = atob(s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4));
    return new TextDecoder().decode(Uint8Array.from(bin, c => c.charCodeAt(0)));
  }
  /** FNV-1a 64 as hex. A mock signature only: the real cursor HMAC lives in dexter-db (KeysetCursorCodec). */
  function fnv64(text) {
    let h = 0xcbf29ce484222325n;
    for (const b of new TextEncoder().encode(text)) { h ^= BigInt(b); h = (h * 0x100000001b3n) & 0xffffffffffffffffn; }
    return h.toString(16).padStart(16, '0');
  }
  /** Stable text of a filter object: sorted keys, empty values dropped (the same filters → the same hash). */
  function canonicalFilters(f) {
    const keys = Object.keys(f || {}).filter(k => f[k] !== undefined && f[k] !== null && f[k] !== '').sort();
    return JSON.stringify(keys.map(k => [k, String(f[k])]));
  }
  function uuidv4() {
    if (global.crypto && global.crypto.randomUUID) return global.crypto.randomUUID();
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
      const r = Math.random() * 16 | 0; return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
    });
  }
  /** Time-ordered mock ids (UUIDv7 layout, like the services mint). */
  function uuidv7(ms = Date.now()) {
    const hex = ms.toString(16).padStart(12, '0');
    const r = uuidv4().replace(/-/g, '');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-7${r.slice(13, 16)}-${((parseInt(r[16], 16) & 0x3) | 0x8).toString(16)}${r.slice(17, 20)}-${r.slice(20, 32)}`;
  }
  function header(headers, name) {
    const n = name.toLowerCase();
    for (const k of Object.keys(headers || {})) if (k.toLowerCase() === n) return headers[k];
    return undefined;
  }

  // ------------------------------------------------------------------ mock responses
  const REPLY = Symbol('reply');
  /** A mock response with an explicit status and headers (ProblemDetail errors, ETags, ...). */
  function reply(status, body, headers = {}) { return { [REPLY]: true, status, body, headers }; }
  /** RFC 9457 ProblemDetail with a stable code, as dexter-web answers it. */
  function problem(status, code, detail, extra = {}) {
    return reply(status, {
      type: 'https://docs.dexter.in/problems/' + code.toLowerCase().replace(/_/g, '-'),
      title: code.replace(/_/g, ' ').toLowerCase().replace(/^\w/, c => c.toUpperCase()),
      status, detail, code, ...extra,
    }, { 'content-type': 'application/problem+json', ...(extra.headers || {}) });
  }

  // ------------------------------------------------------------------ keyset cursor (mock)
  const CURSOR_KEY = 'trail-mock-c1';
  const cursor = {
    encode(payload) {
      const p = b64urlEncode(JSON.stringify({ v: 1, ...payload }));
      return p + '.' + fnv64(CURSOR_KEY + '|' + p);
    },
    /** Returns the payload, or throws {code} like KeysetCursorCodec. */
    decode(c) {
      const dot = typeof c === 'string' ? c.indexOf('.') : -1;
      if (dot <= 0) throw { code: 'CURSOR_INVALID', detail: 'malformed cursor' };
      const p = c.slice(0, dot);
      if (c.slice(dot + 1) !== fnv64(CURSOR_KEY + '|' + p)) throw { code: 'CURSOR_INVALID', detail: 'bad signature' };
      try {
        const payload = JSON.parse(b64urlDecode(p));
        if (payload.v !== 1 || !Array.isArray(payload.k)) throw new Error('shape');
        return payload;
      } catch { throw { code: 'CURSOR_INVALID', detail: 'unreadable cursor' }; }
    },
    /** Unsigned peek for the debug panel. */
    peek(c) { try { return JSON.parse(b64urlDecode(String(c).split('.')[0])); } catch { return null; } },
  };

  /**
   * A keyset-paged collection: rows ordered by (sortKey DESC, id DESC) - the order of
   * `ORDER BY occurred_at DESC, id DESC` with the uuid compared as text (= unsigned bytes, Postgres order).
   * opts: { rows: [] | () => [], sortKey: 'occurredAt', sortName: 'occurred_desc', tenantKey: 'accountId',
   *         filters: { name: (row, value) => bool } }
   */
  function collection(opts) {
    const sortKey = opts.sortKey || 'occurredAt';
    const sortName = opts.sortName || (sortKey + '_desc');
    const tenantKey = opts.tenantKey === undefined ? 'accountId' : opts.tenantKey;
    let rows = typeof opts.rows === 'function' ? null : (opts.rows || []).slice();
    const all = () => (rows || opts.rows());
    const cmp = (a, b) => (a[sortKey] < b[sortKey] ? 1 : a[sortKey] > b[sortKey] ? -1 : (a.id < b.id ? 1 : a.id > b.id ? -1 : 0));
    const before = (row, k) => row[sortKey] < k[0] || (row[sortKey] === k[0] && row.id < k[1]);
    return {
      add(row) { if (rows) rows.push(row); return row; },
      rows: all,
      find(id, tenant) { return all().find(r => r.id === id && (tenantKey === null || r[tenantKey] === tenant)); },
      /** query: {limit, cursor, ...filters}; tenant: accountId (null = no tenant column, global data). */
      list(query, tenant) {
        const q = { ...(query || {}) };
        const limitRaw = q.limit; const c = q.cursor; delete q.limit; delete q.cursor;
        let limit = 25;
        if (limitRaw !== undefined && limitRaw !== null && limitRaw !== '') {
          limit = Number(limitRaw);
          if (!Number.isInteger(limit) || limit < 1 || limit > 100) {
            return problem(400, 'LIMIT_INVALID', 'limit must be between 1 and 100');
          }
        }
        for (const k of Object.keys(q)) {
          if (!(opts.filters || {})[k]) return problem(400, 'VALIDATION_FAILED', 'unknown filter ' + k);
        }
        const f = fnv64(canonicalFilters(q)).slice(0, 16);
        let after = null;
        if (c !== undefined && c !== null && c !== '') {
          let payload;
          try { payload = cursor.decode(c); } catch (e) { return problem(400, e.code, e.detail); }
          if (payload.s !== sortName) return problem(400, 'CURSOR_INVALID', 'cursor is for another sort');
          if (payload.f !== f) return problem(400, 'CURSOR_FILTER_MISMATCH', 'cursor was issued for other filters');
          after = payload.k;
        }
        const matches = all().filter(r => (tenantKey === null || r[tenantKey] === tenant)
          && Object.keys(q).every(k => opts.filters[k](r, q[k]))
          && (after === null || before(r, after)));
        matches.sort(cmp);
        const page = matches.slice(0, limit + 1); // limit + 1: the extra row only says "there is more"
        const hasMore = page.length > limit;
        const data = hasMore ? page.slice(0, limit) : page;
        const last = data[data.length - 1];
        return {
          data,
          page: { limit, hasMore, nextCursor: hasMore ? cursor.encode({ s: sortName, k: [last[sortKey], last.id], f, kid: 'c1' }) : null },
        };
      },
    };
  }

  // ------------------------------------------------------------------ mock router
  /** Register a mock route: Trail.mock('GET', /^\/api\/v1\/x$/, (match, req) => body | Trail.reply(...)) */
  function mock(method, pattern, handler) { mocks.push({ method, pattern, handler }); }
  function mockList(pattern, coll, { tenant = true } = {}) {
    mock('GET', pattern, (m, req) => {
      if (tenant && !req.tenant) return problem(401, 'UNAUTHENTICATED', 'log in as a persona first');
      return coll.list(req.query, tenant ? req.tenant.accountId : null);
    });
  }
  async function fixture(name) {
    const r = await fetch('fixtures/' + name, { cache: 'no-store' });
    if (!r.ok) throw new Error('fixture missing: ' + name);
    return r.json();
  }

  // ------------------------------------------------------------------ mock idempotency engine (MASTER_PLAN 5)
  const idemStore = new Map(); // tenant|key -> {hash, response} | {hash, pending: Promise}
  function stableJson(v) {
    if (Array.isArray(v)) return '[' + v.map(stableJson).join(',') + ']';
    if (v && typeof v === 'object') return '{' + Object.keys(v).sort().map(k => JSON.stringify(k) + ':' + stableJson(v[k])).join(',') + '}';
    return JSON.stringify(v === undefined ? null : v);
  }
  /** Wraps a mock handler like IdempotencyEngine Mode A. */
  function idempotent(handler, { required = true } = {}) {
    return async (m, req) => {
      const key = header(req.headers, 'Idempotency-Key');
      if (!key) {
        if (required) return problem(400, 'IDEMPOTENCY_KEY_REQUIRED', 'Idempotency-Key header is required');
        return handler(m, req);
      }
      if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(key)) {
        return problem(400, 'VALIDATION_FAILED', 'Idempotency-Key must be a UUID');
      }
      const scope = (req.tenant ? req.tenant.accountId : 'anon') + '|' + key.toLowerCase();
      const hash = fnv64(req.method + '|' + req.path + '|' + stableJson(req.body === undefined ? null : req.body));
      let entry = idemStore.get(scope);
      if (entry && entry.hash !== hash) {
        return problem(422, 'IDEMPOTENCY_KEY_REUSED', 'this key was used with a different request');
      }
      if (entry && entry.pending) { await entry.pending; entry = idemStore.get(scope); }
      if (entry && entry.response) {
        const r = entry.response;
        return reply(r.status, JSON.parse(JSON.stringify(r.body)), { ...r.headers, 'idempotent-replayed': 'true' });
      }
      let done;
      idemStore.set(scope, { hash, pending: new Promise(res => { done = res; }) });
      try {
        const out = await handler(m, req);
        const r = out && out[REPLY] ? out : reply(200, out);
        if (r.status >= 500) idemStore.delete(scope); // a failed attempt leaves no key behind (transaction rolled back)
        else idemStore.set(scope, { hash, response: { status: r.status, body: JSON.parse(JSON.stringify(r.body)), headers: r.headers } });
        return r;
      } catch (e) { idemStore.delete(scope); throw e; } finally { done(); }
    };
  }

  // ------------------------------------------------------------------ mock outbox / events
  const eventListeners = [];
  const events = {
    log: [],
    emit(type, aggregateId, data, accountId) {
      const e = { id: uuidv7(), type, version: 1, source: type.split('.')[0], time: new Date().toISOString(),
        accountId: accountId || null, aggregateId, aggregateVersion: data && data.version !== undefined ? data.version : 0, data };
      events.log.unshift(e);
      if (events.log.length > 200) events.log.length = 200;
      eventListeners.forEach(fn => fn(e));
      return e;
    },
    recent(limit = 50) { return events.log.slice(0, limit); },
    clear() { events.log.length = 0; },
  };

  // ------------------------------------------------------------------ personas (tenant switcher)
  const auth = { personas: [], current: null, tokens: {} };
  const personaListeners = [];
  function setPersonas(list) {
    auth.personas = list.slice();
    const saved = safeGet(PERSONA_KEY, '');
    if (!auth.current && saved) auth.current = auth.personas.find(p => p.key === saved) || null;
  }
  function usePersona(key) {
    auth.current = key ? auth.personas.find(p => p.key === key) || null : null;
    safeSet(PERSONA_KEY, auth.current ? auth.current.key : '');
    personaListeners.forEach(fn => fn(auth.current));
    return auth.current;
  }
  /** Live: POST /api/v1/auth/login (identity, P04). Mock: accepted for the persona's demo password. */
  async function login(key, password) {
    const p = auth.personas.find(x => x.key === key);
    if (!p) throw new Error('unknown persona ' + key);
    const path = p.role === 'USER' ? '/api/v1/auth/login' : '/api/v1/admin/auth/login';
    const r = await api('POST', path, { body: { email: p.email, password } });
    if (r.status === 200 && r.data && r.data.accessToken) { auth.tokens[key] = r.data.accessToken; usePersona(key); }
    return r;
  }

  // ------------------------------------------------------------------ api()
  function parseQuery(path) {
    const i = path.indexOf('?');
    const q = {};
    if (i >= 0) for (const [k, v] of new URLSearchParams(path.slice(i + 1))) q[k] = v;
    return { pathOnly: i >= 0 ? path.slice(0, i) : path, query: q };
  }

  /**
   * P04/P05 bridged identity calls to /svc/identity until the gateway routed /api/* (A-36). From P06 every Live call
   * goes /api/... → trail nginx → gateway :8080 → service, so the list is empty; it stays as a hook for a page that
   * must reach a service directly while debugging.
   */
  const liveRoutes = [];
  function livePath(path) {
    for (const [from, to] of liveRoutes) if (path.startsWith(from)) return to + path.slice(from.length);
    return path;
  }

  async function api(method, path, { body, raw, headers = {} } = {}) {
    const requestId = headers['X-Request-Id'] || newRequestId();
    const started = performance.now();
    const entry = { id: requestId, method, path, mode, status: 0, ms: 0, error: null, persona: auth.current ? auth.current.key : null };
    try {
      let status, data, resHeaders = {};
      if (mode === 'mock') {
        const { pathOnly, query } = parseQuery(path);
        const route = mocks.find(m => m.method === method && m.pattern.test(pathOnly));
        if (!route) { status = 501; data = { code: 'NO_MOCK', detail: 'no mock for ' + method + ' ' + pathOnly }; }
        else {
          const [lo, hi] = settings.latencyMs;
          if (hi > 0) await new Promise(r => setTimeout(r, lo + Math.random() * (hi - lo)));
          const out = await route.handler(pathOnly.match(route.pattern),
            { method, path: pathOnly, query, body, raw, headers, tenant: auth.current });
          if (out && out[REPLY]) { status = out.status; data = out.body; resHeaders = { ...out.headers }; }
          else { data = out; status = 200; }
          if (data && typeof data === 'object' && data.code && status >= 400) data = { ...data, requestId };
          resHeaders['x-request-id'] = requestId;
        }
      } else {
        const token = auth.current && auth.tokens[auth.current.key];
        const res = await fetch(livePath(path), {
          method,
          headers: { 'Accept': 'application/json', 'X-Request-Id': requestId,
                     ...(token ? { 'Authorization': 'Bearer ' + token } : {}),
                     ...(body || raw !== undefined ? { 'Content-Type': 'application/json' } : {}), ...headers },
          body: raw !== undefined ? raw : body ? JSON.stringify(body) : undefined,
        });
        status = res.status;
        res.headers.forEach((v, k) => { resHeaders[k] = v; });
        const text = await res.text();
        try { data = text ? JSON.parse(text) : null; } catch { data = text; }
      }
      entry.status = status;
      return { status, data, headers: resHeaders, requestId };
    } catch (e) {
      entry.error = e && e.message ? e.message : String(e);
      return { status: 0, data: null, headers: {}, requestId, error: entry.error };
    } finally {
      entry.ms = Math.round(performance.now() - started);
      logListeners.forEach(fn => fn(entry));
    }
  }

  // ------------------------------------------------------------------ pagination window (MASTER_PLAN 4.4)
  /**
   * The client window: always holds one page more than it shows, so scrolling never waits on the network.
   * fetchPage({limit, cursor}) → {data, page:{hasMore, nextCursor}} (or throws). Items are 1-based in onItemVisible.
   */
  class Pager {
    constructor({ fetchPage, first = 50, page = 25, triggerOffset = 1, onChange = () => {} }) {
      this.fetchPage = fetchPage; this.FIRST = first; this.PAGE = page; this.TRIGGER_OFFSET = triggerOffset;
      this.onChange = onChange;
      this.reset();
    }
    reset() {
      this.buffer = []; this.shown = 0; this.cursor = null; this.hasMore = true; this.inflight = null;
      this.ids = new Set(); this.fetches = []; this.duplicatesDropped = 0; this.error = null; this.generation = (this.generation || 0) + 1;
    }
    get state() {
      return { buffered: this.buffer.length, shown: this.shown, hasMore: this.hasMore, inflight: !!this.inflight,
        cursor: this.cursor, cursorPayload: this.cursor ? cursor.peek(this.cursor) : null, fetches: this.fetches.slice(),
        duplicatesDropped: this.duplicatesDropped, error: this.error };
    }
    append(rows) {
      for (const r of rows) {
        if (this.ids.has(r.id)) { this.duplicatesDropped++; continue; } // dedupeById
        this.ids.add(r.id); this.buffer.push(r);
      }
    }
    async load(limit) {
      const gen = this.generation;
      const t0 = Date.now();
      const r = await this.fetchPage({ limit, cursor: this.cursor });
      if (gen !== this.generation) return; // reset while in flight: drop the stale page
      if (!r || !r.page) { this.error = (r && (r.code || r.detail)) || 'fetch failed'; this.hasMore = false; return; }
      this.append(r.data);
      this.cursor = r.page.nextCursor; this.hasMore = r.page.hasMore;
      this.fetches.push({ limit, rows: r.data.length, ms: Date.now() - t0, hasMore: r.page.hasMore });
    }
    async init() {
      this.reset();
      this.inflight = this.load(this.FIRST);
      await this.inflight; this.inflight = null;
      this.shown = Math.min(this.PAGE, this.buffer.length); // display 1–25; 26–50 are already buffered
      this.onChange(this.state);
      return this.state;
    }
    /** Called when item i (1-based) becomes visible. Resolves when anything it started has settled. */
    async onItemVisible(i) {
      let started = null;
      if (i >= this.shown - this.TRIGGER_OFFSET) {                          // the user reached item 24 of 25
        if (this.shown < this.buffer.length) this.shown = Math.min(this.shown + this.PAGE, this.buffer.length); // instantly
        if (this.hasMore && !this.inflight) {                                  // fetch the next page in the background
          started = this.inflight = this.load(this.PAGE).then(() => { this.inflight = null; this.onChange(this.state); });
        }
        this.onChange(this.state);
      }
      if (i >= this.buffer.length && this.inflight) {                         // ran past the buffer: skeleton + wait
        this.onChange({ ...this.state, skeleton: true });
        await this.inflight;
        if (this.shown < this.buffer.length) this.shown = Math.min(this.shown + this.PAGE, this.buffer.length);
        this.onChange(this.state);
      } else if (started) {
        await started;
      }
      return this.state;
    }
  }
  function pager(opts) { return new Pager(opts); }

  // ------------------------------------------------------------------ seed fixtures → standard mock API
  /** Mock routes for the seed fixtures (shapes are the planned P04/P07/P09/P11 API responses). */
  function useSeed(seed) {
    setPersonas(seed.personas);
    const expenses = collection({
      rows: seed.expenses.slice(),
      sortKey: 'occurredAt', sortName: 'occurred_desc',
      filters: {
        category: (r, v) => r.categoryPath === v || r.categoryPath.startsWith(v + '.'),
        paymentMethod: (r, v) => r.paymentMethod === v,
        cardId: (r, v) => r.userCardId === v,
        merchantId: (r, v) => r.merchantId === v,
        from: (r, v) => r.occurredAt >= v,
        to: (r, v) => r.occurredAt < v,
        minPaise: (r, v) => r.amountPaise >= Number(v),
        maxPaise: (r, v) => r.amountPaise <= Number(v),
      },
    });
    const catalog = collection({ rows: seed.catalogCards.map(c => ({ ...c, sortName: c.name })), sortKey: 'sortName',
      sortName: 'name_desc', tenantKey: null, filters: { network: (r, v) => r.network === v, tier: (r, v) => r.tier === v } });
    const userCards = collection({ rows: seed.userCards.map(c => ({ ...c, sortName: c.nickname })), sortKey: 'sortName',
      sortName: 'nickname_desc', filters: {} });

    const auth401 = req => (req.tenant ? null : problem(401, 'UNAUTHENTICATED', 'log in as a persona first'));
    mockList(/^\/api\/v1\/expenses$/, expenses);
    mock('GET', /^\/api\/v1\/expenses\/([0-9a-f-]{36})$/, (m, req) => auth401(req) || (() => {
      const e = expenses.find(m[1], req.tenant.accountId); // another tenant's id is simply not found (RLS)
      return e ? reply(200, e, { etag: '"' + e.version + '"' }) : problem(404, 'NOT_FOUND', 'expense not found');
    })());
    mock('POST', /^\/api\/v1\/expenses$/, idempotent((m, req) => {
      const denied = auth401(req); if (denied) return denied;
      const b = req.body || {};
      const errors = [];
      if (!Number.isInteger(b.amountPaise) || b.amountPaise <= 0) errors.push({ field: 'amountPaise', code: 'POSITIVE', message: 'must be a positive integer (paise)' });
      if (!b.occurredAt || isNaN(Date.parse(b.occurredAt))) errors.push({ field: 'occurredAt', code: 'INSTANT', message: 'ISO-8601 instant required' });
      if (errors.length) return problem(400, 'VALIDATION_FAILED', 'Request validation failed', { errors });
      const now = Date.now();
      const row = expenses.add({ id: uuidv7(now), accountId: req.tenant.accountId, occurredAt: new Date(b.occurredAt).toISOString(),
        amountPaise: b.amountPaise, currency: 'INR', merchantDisplay: b.merchant || 'Manual entry', merchantId: null,
        categoryPath: b.categoryPath || 'general', categorySource: b.categoryPath ? 'user' : 'resolver', resolutionConfidence: b.categoryPath ? 1000 : 0,
        paymentMethod: b.paymentMethod || 'upi', userCardId: null, note: b.note || null, tags: b.tags || [], source: 'manual', version: 0,
        createdAt: new Date(now).toISOString() });
      events.emit('ledger.expense.created', row.id, { after: row }, req.tenant.accountId);
      return reply(201, row, { location: '/v1/expenses/' + row.id, etag: '"0"' });
    }));
    mock('GET', /^\/api\/v1\/categories$/, () => ({ data: seed.categories }));
    mockList(/^\/api\/v1\/cards$/, userCards);
    mockList(/^\/api\/v1\/catalog\/cards$/, catalog, { tenant: false });
    mock('GET', /^\/api\/v1\/me$/, (m, req) => auth401(req) || req.tenant);
    mock('POST', /^\/api\/v1\/(auth|admin\/auth)\/login$/, (m, req) => {
      const p = auth.personas.find(x => x.email === (req.body || {}).email);
      if (!p || !req.body.password) return problem(401, 'UNAUTHENTICATED', 'invalid email or password');
      return { accessToken: 'mock.' + p.key, tokenType: 'Bearer', expiresIn: 600 };
    });
    mock('GET', /^\/api\/_dev\/outbox\/recent$/, () => ({ data: events.recent() }));
    return { expenses, catalog, userCards };
  }
  /** Browser: fetch the seed fixtures and register the routes. */
  async function loadSeed() {
    const [p, e, c, m, cc, uc] = await Promise.all(['personas', 'expenses', 'categories', 'merchants', 'catalog-cards', 'user-cards']
      .map(n => fixture('seed/' + n + '.json')));
    return useSeed({ personas: p.data, expenses: e.data, categories: c.data, merchants: m.data, catalogCards: cc.data, userCards: uc.data });
  }

  // ------------------------------------------------------------------ DOM: header, log
  function el(tag, attrs = {}, html) {
    const e = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
    if (html !== undefined) e.innerHTML = html;
    return e;
  }
  function esc(s) { return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); }

  function mountHeader(title, phase) {
    const h = el('header', { class: 'trail' });
    h.innerHTML = `
      <h1>Dexter trail<small>${esc(phase)} · ${esc(title)}</small></h1>
      <div style="display:flex;gap:12px;align-items:center;flex-wrap:wrap">
        <a href="index.html">All pages</a>
        <div class="mode" role="group" aria-label="Data source">
          <button type="button" data-mode="mock">Mock</button>
          <button type="button" data-mode="live">Live</button>
        </div>
      </div>`;
    document.body.prepend(h);
    const sync = () => h.querySelectorAll('[data-mode]').forEach(b =>
      b.setAttribute('aria-pressed', String(b.dataset.mode === mode)));
    h.querySelectorAll('[data-mode]').forEach(b => b.addEventListener('click', () => {
      mode = b.dataset.mode; safeSet(MODE_KEY, mode); sync();
      document.dispatchEvent(new CustomEvent('trail:mode', { detail: mode }));
    }));
    sync();
  }

  function mountLog(container) {
    container.innerHTML = `<div class="row" style="justify-content:space-between"><h2 style="margin:0">Request log</h2>
      <span><span class="mono" data-n>0</span> requests <button class="ghost" type="button" data-clear>Clear</button></span></div>
      <div class="log" aria-live="polite"></div>`;
    const box = container.querySelector('.log');
    const count = container.querySelector('[data-n]');
    let n = 0;
    container.querySelector('[data-clear]').addEventListener('click', () => { box.innerHTML = ''; n = 0; count.textContent = '0'; });
    logListeners.push(e => {
      const row = document.createElement('div');
      const cls = e.error || e.status >= 500 || e.status === 0 ? 'bad' : e.status >= 400 ? 'warn' : 'ok';
      row.innerHTML = `<span class="${cls}">${e.status || 'ERR'}</span> ${esc(e.method)} ${esc(e.path)}
        · ${e.ms}ms · ${e.mode}${e.persona ? ' · ' + esc(e.persona) : ''} · <span title="X-Request-Id">${esc(e.id)}</span>${e.error ? ' · ' + esc(e.error) : ''}`;
      box.prepend(row);
      count.textContent = String(++n);
    });
  }

  // ------------------------------------------------------------------ DOM: tenant switcher
  function mountTenantSwitcher(container, { onSwitch } = {}) {
    container.innerHTML = `<h2>Tenant</h2>
      <div class="row" role="group" aria-label="Persona" data-personas></div>
      <div class="row"><input class="in" type="password" placeholder="password (Live: from .env DEMO_PASSWORD_*)" data-pw style="flex:1;min-width:200px">
        <button class="ghost" type="button" data-login>Live login</button></div>
      <pre class="out" data-out>Mock: pick a persona - mock data is scoped to that account, like RLS. Live: identity login arrives in P04.</pre>`;
    const box = container.querySelector('[data-personas]');
    const out = container.querySelector('[data-out]');
    const render = () => {
      box.innerHTML = '';
      for (const p of auth.personas) {
        const b = el('button', { type: 'button', class: 'ghost', 'aria-pressed': String(!!auth.current && auth.current.key === p.key) },
          `${esc(p.displayName)} <small class="mono">${esc(p.role)}</small>`);
        b.addEventListener('click', () => { usePersona(p.key); out.textContent = describe(); onSwitch && onSwitch(auth.current); });
        box.appendChild(b);
      }
      const none = el('button', { type: 'button', class: 'ghost', 'aria-pressed': String(!auth.current) }, 'Nobody');
      none.addEventListener('click', () => { usePersona(null); out.textContent = describe(); onSwitch && onSwitch(null); });
      box.appendChild(none);
    };
    const describe = () => auth.current
      ? `${auth.current.displayName} <${auth.current.email}>\n${auth.current.accountId ? 'accountId ' + auth.current.accountId : 'adminId ' + auth.current.adminId}\nrole ${auth.current.role} · tz ${auth.current.tz}${auth.current.tier ? ' · tier ' + auth.current.tier : ''}\ntoken: ${auth.tokens[auth.current.key] ? 'yes' : mode === 'mock' ? 'not needed in Mock' : 'none - Live login first'}`
      : 'Nobody logged in: tenant endpoints answer 401 UNAUTHENTICATED.';
    container.querySelector('[data-login]').addEventListener('click', async () => {
      if (!auth.current) { out.textContent = 'Pick a persona first.'; return; }
      const r = await login(auth.current.key, container.querySelector('[data-pw]').value);
      out.textContent = `POST login → ${r.status}\n${JSON.stringify(r.data, null, 2)}\n\n${describe()}`;
    });
    personaListeners.push(() => { render(); out.textContent = describe(); });
    render();
    if (auth.current) out.textContent = describe();
  }

  // ------------------------------------------------------------------ DOM: pagination window + debug panel
  /**
   * opts: { path: '/api/v1/expenses', query: () => ({category: ...}), render: row => html, first, page, triggerOffset }
   * Returns the Pager (reload() re-inits it, e.g. after a filter or tenant change).
   */
  function mountPager(container, opts) {
    container.innerHTML = `<div class="grid2">
      <div><div class="row" style="justify-content:space-between"><strong>Window</strong>
        <span class="mono" data-summary></span></div>
        <div class="list" data-list style="max-height:420px" tabindex="0" aria-label="Paged list"></div></div>
      <div><strong>Debug</strong><div class="kv" data-debug style="margin-top:6px"></div>
        <pre class="out" data-cursor style="max-height:120px">cursor: —</pre>
        <div class="list" data-fetches style="max-height:150px;margin-top:8px"></div></div></div>`;
    const list = container.querySelector('[data-list]');
    const debug = container.querySelector('[data-debug]');
    const cur = container.querySelector('[data-cursor]');
    const fetchesBox = container.querySelector('[data-fetches]');
    const summary = container.querySelector('[data-summary]');
    let lastError = null;
    const p = new Pager({
      first: opts.first, page: opts.page, triggerOffset: opts.triggerOffset,
      fetchPage: async ({ limit, cursor: c }) => {
        const q = new URLSearchParams({ ...(opts.query ? opts.query() : {}), limit: String(limit) });
        if (c) q.set('cursor', c);
        const r = await api('GET', opts.path + '?' + q.toString());
        if (r.status !== 200) { lastError = r.data; return r.data; }
        lastError = null;
        return r.data;
      },
      onChange: s => paint(s),
    });
    const observer = typeof IntersectionObserver !== 'undefined' ? new IntersectionObserver(entries => {
      for (const en of entries) if (en.isIntersecting) p.onItemVisible(Number(en.target.dataset.i));
    }, { root: list, threshold: 0.6 }) : null;
    let painted = 0;
    function paint(s) {
      while (painted < p.shown) {
        const r = p.buffer[painted];
        const row = el('div', { 'data-i': String(painted + 1) }, `<span style="color:var(--muted)">${String(painted + 1).padStart(3, ' ')}</span> ${opts.render(r)}`);
        list.appendChild(row);
        if (observer) observer.observe(row);
        painted++;
      }
      list.querySelectorAll('.skel').forEach(x => x.remove());
      if (s.skeleton) list.appendChild(el('div', { class: 'skel' }, 'loading…'));
      if (!s.hasMore && painted === p.buffer.length && painted > 0) list.appendChild(el('div', { class: 'skel' }, '— end —'));
      const shownFrom = p.shown ? 1 : 0;
      summary.textContent = `showing ${shownFrom}–${p.shown} · buffered ${p.buffer.length ? 1 : 0}–${p.buffer.length}`;
      debug.innerHTML = [
        ['FIRST / PAGE / TRIGGER', `${p.FIRST} / ${p.PAGE} / item shown−${p.TRIGGER_OFFSET}`],
        ['shown range', `1–${p.shown}`], ['buffer range', `1–${p.buffer.length}`],
        ['hasMore', String(s.hasMore)], ['in-flight fetch', s.inflight ? '<span class="warn">yes</span>' : 'no'],
        ['duplicates dropped', String(s.duplicatesDropped)], ['fetches', String(s.fetches.length)],
        ['last error', lastError ? `<span class="bad">${esc(lastError.code || 'error')}</span>` : '—'],
      ].map(([k, v]) => `<span>${esc(k)}</span><span>${v}</span>`).join('');
      cur.textContent = s.cursor ? `cursor: ${s.cursor}\npayload: ${JSON.stringify(s.cursorPayload)}` : 'cursor: — (no next page)';
      fetchesBox.innerHTML = s.fetches.map((f, i) => `<div>#${i + 1} limit=${f.limit} → ${f.rows} rows · ${f.ms}ms · hasMore=${f.hasMore}</div>`).join('');
    }
    p.reload = async () => { list.innerHTML = ''; painted = 0; list.scrollTop = 0; await p.init(); };
    return p;
  }

  // ------------------------------------------------------------------ DOM: idempotency tester
  /** opts: { method: 'POST', path, body: () => object, label } */
  function mountIdempotencyTester(container, opts) {
    container.innerHTML = `<h2>Idempotency-Key tester</h2>
      <p class="mono" style="color:var(--muted);margin:0 0 6px">${esc(opts.method)} ${esc(opts.path)} · MASTER_PLAN 5</p>
      <div class="row">
        <button class="act" type="button" data-t="twice">Same key twice</button>
        <button class="ghost" type="button" data-t="parallel">Same key × 20 parallel</button>
        <button class="ghost" type="button" data-t="reuse">Same key, different body</button>
        <button class="ghost" type="button" data-t="nokey">No key</button>
      </div><pre class="out" data-out>—</pre>`;
    const out = container.querySelector('[data-out]');
    const send = (key, body) => api(opts.method, opts.path, { body, headers: key ? { 'Idempotency-Key': key } : {} });
    const line = r => `${r.status} ${r.data && r.data.code ? r.data.code : ''}${r.headers['idempotent-replayed'] ? ' · Idempotent-Replayed: true' : ''}${r.data && r.data.id ? ' · id ' + r.data.id : ''}`;
    container.querySelectorAll('[data-t]').forEach(b => b.addEventListener('click', async () => {
      const key = uuidv4(); const body = opts.body();
      out.textContent = 'sending…';
      let text;
      if (b.dataset.t === 'twice') {
        const r1 = await send(key, body); const r2 = await send(key, body);
        text = `key ${key}\n1st: ${line(r1)}\n2nd: ${line(r2)}\nsame body: ${JSON.stringify(r1.data) === JSON.stringify(r2.data)}`;
      } else if (b.dataset.t === 'parallel') {
        const rs = await Promise.all(Array.from({ length: 20 }, () => send(key, body)));
        const ids = new Set(rs.map(r => r.data && r.data.id).filter(Boolean));
        const tally = {}; rs.forEach(r => { const k = r.status + (r.headers['idempotent-replayed'] ? ' replayed' : ''); tally[k] = (tally[k] || 0) + 1; });
        text = `key ${key}\n20 parallel → ${JSON.stringify(tally)}\ndistinct ids created: ${ids.size} (expected 1)`;
      } else if (b.dataset.t === 'reuse') {
        const r1 = await send(key, body); const r2 = await send(key, { ...body, amountPaise: (body.amountPaise || 1) + 1 });
        text = `key ${key}\n1st: ${line(r1)}\n2nd (different body): ${line(r2)} (expected 422 IDEMPOTENCY_KEY_REUSED)`;
      } else {
        const r = await send(null, body);
        text = `no key: ${line(r)} (expected 400 IDEMPOTENCY_KEY_REQUIRED where the phase requires it)`;
      }
      out.textContent = text;
      opts.after && opts.after();
    }));
  }

  // ------------------------------------------------------------------ DOM: event viewer
  /**
   * Mock: shows the mock outbox as writes happen. Live: polls opts.livePath (a dev-only outbox endpoint) when a
   * phase provides one; until then it says so (stub).
   */
  function mountEvents(container, { livePath = null, everyMs = 2000 } = {}) {
    container.innerHTML = `<div class="row" style="justify-content:space-between"><h2 style="margin:0">Events (outbox)</h2>
      <button class="ghost" type="button" data-refresh>Refresh</button></div><div class="list" data-list style="max-height:220px"></div>`;
    const box = container.querySelector('[data-list]');
    const show = list => {
      box.innerHTML = list.length ? list.map(e => `<div>${esc(e.time || e.createdAt || '')} <strong>${esc(e.type || e.eventType)}</strong> agg=${esc(String(e.aggregateId || '').slice(0, 13))}… v${esc(e.aggregateVersion ?? '')}${e.accountId ? ' acct=' + esc(String(e.accountId).slice(0, 8)) : ''}</div>`).join('')
        : '<div class="skel">no events yet</div>';
    };
    const refresh = async () => {
      if (mode === 'mock') { show(events.recent()); return; }
      if (!livePath) { box.innerHTML = '<div class="skel">Live: no outbox endpoint on this page yet (services emit events from P04/P07; MASTER_PLAN 11.2 /_dev/outbox/recent).</div>'; return; }
      const r = await api('GET', livePath);
      show(r.status === 200 && r.data ? (r.data.data || r.data) : []);
    };
    container.querySelector('[data-refresh]').addEventListener('click', refresh);
    eventListeners.push(() => { if (mode === 'mock') show(events.recent()); });
    document.addEventListener('trail:mode', refresh);
    if (livePath) setInterval(() => { if (mode === 'live') refresh(); }, everyMs);
    refresh();
  }

  const Trail = {
    api, mock, reply, problem, fixture, mountHeader, mountLog,
    get mode() { return mode; }, setMode(m) { mode = m; },
    settings, cursor, collection, mockList, idempotent, events, Pager, pager,
    personas: () => auth.personas, get persona() { return auth.current; }, setPersonas, usePersona, login,
    useSeed, loadSeed, mountTenantSwitcher, mountPager, mountIdempotencyTester, mountEvents,
    uuidv4, uuidv7, liveRoutes, livePath, b64urlDecode, _reset() { mocks.length = 0; idemStore.clear(); events.clear(); auth.current = null; auth.tokens = {}; },
  };
  global.Trail = Trail;
  if (typeof module !== 'undefined' && module.exports) module.exports = Trail;
})(typeof window !== 'undefined' ? window : globalThis);
