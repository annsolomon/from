// Trail kit (MASTER_PLAN 11.2) - THROWAWAY, deleted in P19.
// P00 skeleton: Mock/Live toggle, Trail.api(), request log with timings.
// P01: mock handlers may return Trail.reply(status, body, headers); api() accepts {raw} bodies
//      (for duplicate-key JSON, which JSON.stringify cannot produce).
// P03 adds: login + tenant switcher, pagination window (MASTER_PLAN 4.4) + debug panel,
//           idempotency tester, event viewer.
(function () {
  'use strict';
  const MODE_KEY = 'dexter.trail.mode';
  const mocks = [];
  const logListeners = [];
  const safeGet = (k, d) => { try { return localStorage.getItem(k) || d; } catch { return d; } };
  const safeSet = (k, v) => { try { localStorage.setItem(k, v); } catch { /* ignore */ } };

  let mode = safeGet(MODE_KEY, 'mock');
  let seq = 0;

  function newRequestId() {
    return 'trail-' + Date.now().toString(36) + '-' + (++seq);
  }

  /** Register a mock route: Trail.mock('GET', /^\/svc\/(\w+)\/actuator\/health$/, (match, req) => body) */
  function mock(method, pattern, handler) { mocks.push({ method, pattern, handler }); }

  const REPLY = Symbol('reply');
  /** A mock response with an explicit status and headers (ProblemDetail errors, ETags, ...). */
  function reply(status, body, headers = {}) { return { [REPLY]: true, status, body, headers }; }

  async function fixture(name) {
    const r = await fetch('fixtures/' + name, { cache: 'no-store' });
    if (!r.ok) throw new Error('fixture missing: ' + name);
    return r.json();
  }

  async function api(method, path, { body, raw, headers = {} } = {}) {
    const requestId = headers['X-Request-Id'] || newRequestId();
    const started = performance.now();
    const entry = { id: requestId, method, path, mode, status: 0, ms: 0, error: null };
    try {
      let status, data, resHeaders = {};
      if (mode === 'mock') {
        const route = mocks.find(m => m.method === method && m.pattern.test(path));
        if (!route) { status = 501; data = { code: 'NO_MOCK', detail: 'no mock for ' + method + ' ' + path }; }
        else {
          await new Promise(r => setTimeout(r, 40 + Math.random() * 80)); // simulated latency
          const out = await route.handler(path.match(route.pattern), { method, path, body, raw, headers });
          if (out && out[REPLY]) { status = out.status; data = out.body; resHeaders = { ...out.headers }; }
          else { data = out; status = 200; }
          resHeaders['x-request-id'] = requestId;
        }
      } else {
        const res = await fetch(path, {
          method,
          headers: { 'Accept': 'application/json', 'X-Request-Id': requestId,
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
      entry.error = e.message;
      return { status: 0, data: null, headers: {}, requestId, error: e.message };
    } finally {
      entry.ms = Math.round(performance.now() - started);
      logListeners.forEach(fn => fn(entry));
    }
  }

  function mountHeader(title, phase) {
    const h = document.createElement('header');
    h.className = 'trail';
    h.innerHTML = `
      <h1>Dexter trail<small>${phase} · ${title}</small></h1>
      <div style="display:flex;gap:12px;align-items:center">
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
    container.innerHTML = '<h2>Request log</h2><div class="log" aria-live="polite"></div>';
    const box = container.querySelector('.log');
    logListeners.push(e => {
      const row = document.createElement('div');
      const cls = e.error || e.status >= 500 || e.status === 0 ? 'bad' : e.status >= 400 ? 'warn' : 'ok';
      row.innerHTML = `<span class="${cls}">${e.status || 'ERR'}</span> ${e.method} ${e.path}
        · ${e.ms}ms · ${e.mode} · <span title="X-Request-Id">${e.id}</span>${e.error ? ' · ' + e.error : ''}`;
      box.prepend(row);
    });
  }

  window.Trail = { api, mock, reply, fixture, mountHeader, mountLog, get mode() { return mode; } };
})();
