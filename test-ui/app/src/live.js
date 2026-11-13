/* ---------- LIVE mode: the same UI against the real stack (gateway behind the trail nginx at /api) ---------- */
const LIVE = { base: '/api', tokens: {}, cache: {} };
try { state.live = localStorage.getItem('dexter.mode') === 'live'; } catch { state.live = false; }
// dev-only demo passwords from .env.example (DEMO_PASSWORD_*); change them here if your .env differs
const DEMO = { asha: ['asha@demo.in', 'Asha-Demo-2026!'], ravi: ['ravi@demo.in', 'Ravi-Demo-2026!'], admin: ['admin@demo.in', 'Admin-Demo-2026!'] };
const IDEM_EXEMPT = /^\/(v1\/calc\/|v1\/auth\/|v1\/admin\/auth\/|v1\/webhooks\/|r\/|\.well-known|_dev\/|oauth2\/|v1\/recommendations\/stream-ticket)/;
const canon = (h) => (h.toLowerCase() === 'etag' ? 'ETag' : h.split('-').map((p) => p[0].toUpperCase() + p.slice(1)).join('-'));
function liveApi(method, url, opt = {}) {
  const t0 = performance.now(); const path = url.split('?')[0];
  const route = REGISTRY.find((r) => r.m === method && r.rx.test(path)); if (route) route.hits++;
  const headers = { Accept: 'application/json', 'X-Request-Id': uuid7(), ...(opt.headers || {}) };
  const tok = opt.token === undefined ? LIVE.tokens[state.persona] : (opt.token && opt.token.live ? opt.token : null);
  if (opt.body !== undefined) headers['Content-Type'] = 'application/json';
  if (['POST', 'PUT', 'PATCH'].includes(method) && !headers['Idempotency-Key'] && !opt.noAutoIdem && !IDEM_EXEMPT.test(path)) headers['Idempotency-Key'] = uuid7();
  let resp;
  try {
    const x = new XMLHttpRequest(); x.open(method, LIVE.base + url, false);
    for (const [k, v] of Object.entries(headers)) x.setRequestHeader(k, v);
    if (tok) x.setRequestHeader('Authorization', 'Bearer ' + tok.access);
    x.send(opt.body !== undefined ? JSON.stringify(opt.body) : null);
    const hs = {}; x.getAllResponseHeaders().trim().split(/\r?\n/).filter(Boolean).forEach((l) => { const i = l.indexOf(':'); hs[canon(l.slice(0, i).trim())] = l.slice(i + 1).trim(); });
    let body = null; const txt = x.responseText; if (txt) { try { body = JSON.parse(txt); } catch { body = { raw: txt.slice(0, 2000) }; } }
    resp = { status: x.status, headers: hs, body };
  } catch (e) {
    resp = path.startsWith('/r/') ? { status: 302, headers: {}, body: { code: 'REDIRECT_FOLLOWED', detail: 'The gateway answered 302 and the browser followed it to the merchant (cross-origin). Opened in a new tab instead.' } }
      : { status: 0, headers: {}, body: { code: 'NETWORK', detail: `Could not reach ${LIVE.base}${url} — is the stack up and is this page served by ./scripts/run.sh trail (http://localhost:8099/app/)?` } };
  }
  if (resp.status < 300 && resp.body && resp.body.accessToken) {
    const who = path.startsWith('/v1/admin/') ? 'admin' : LIVE.loginAs || state.persona;
    const prev = LIVE.tokens[who] || {};
    LIVE.tokens[who] = { live: true, kind: who === 'admin' ? 'admin' : 'user', access: resp.body.accessToken, refresh: resp.body.refreshToken || prev.refresh, stepUpAt: nowIso(), perms: prev.perms || [] };
    LIVE.loginAs = null;
  }
  LOG.unshift({ at: nowIso(), method, url, svc: route ? route.svc : '—', reqHeaders: { ...headers, ...(tok ? { Authorization: 'Bearer ' + tok.access.slice(0, 16) + '…' } : {}) }, body: opt.body, resp, ms: (performance.now() - t0).toFixed(1), live: true });
  if (LOG.length > 300) LOG.pop(); renderLog();
  return resp;
}
async function totp(secret, offset = 0) {
  const al = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'; let bits = ''; for (const c of secret.replace(/=+$/, '').toUpperCase()) { const v = al.indexOf(c); if (v >= 0) bits += v.toString(2).padStart(5, '0'); }
  const key = new Uint8Array(Math.floor(bits.length / 8)); for (let i = 0; i < key.length; i++) key[i] = parseInt(bits.slice(i * 8, i * 8 + 8), 2);
  const ctr = Math.floor(Date.now() / 30000) + offset; const msg = new Uint8Array(8); let c = ctr; for (let i = 7; i >= 0; i--) { msg[i] = c & 255; c = Math.floor(c / 256); }
  const k = await crypto.subtle.importKey('raw', key, { name: 'HMAC', hash: 'SHA-1' }, false, ['sign']); const h = new Uint8Array(await crypto.subtle.sign('HMAC', k, msg));
  const o = h[19] & 15; const n = ((h[o] & 127) << 24 | h[o + 1] << 16 | h[o + 2] << 8 | h[o + 3]) % 1e6; return String(n).padStart(6, '0');
}
const adminSecret = () => { try { return localStorage.getItem('dexter.adminTotp') || ''; } catch { return ''; } };
async function liveAdminLogin(secretIn) {
  const [e, p] = DEMO.admin; const r = api('POST', '/v1/admin/auth/login', { body: { email: e, password: p }, token: null });
  if (r.status !== 200) return toast(`Admin login ${r.status} ${(r.body || {}).code || ''}`);
  let secret = secretIn || adminSecret();
  if (r.body.setup) { const s = api('POST', '/v1/admin/auth/mfa/setup', { body: { mfaToken: r.body.mfaToken }, token: null }); if (s.status !== 200) return toast('MFA setup failed'); secret = s.body.secret; }
  if (!secret) return toast('Admin TOTP secret needed — paste it on Admins & accounts (first sign-in sets it up and remembers it).', 5000);
  try { localStorage.setItem('dexter.adminTotp', secret); } catch { }
  const v = api('POST', '/v1/admin/auth/mfa/verify', { body: { mfaToken: r.body.mfaToken, code: await totp(secret) }, token: null });
  if (v.status === 200) { const me = api('GET', '/v1/admin/me', { token: LIVE.tokens.admin }); if (me.status === 200) LIVE.tokens.admin.perms = me.body.permissions || []; toast('Admin signed in (live)'); render(); }
  else toast(`Admin MFA ${v.status} ${(v.body || {}).code || ''}`);
}
function ensureLiveLogin(who = state.persona) {
  if (!state.live || who === 'anon' || LIVE.tokens[who]) return;
  if (who === 'admin') { liveAdminLogin(); return; }
  const [e, p] = DEMO[who]; LIVE.loginAs = who;
  const r = api('POST', '/v1/auth/login', { body: { email: e, password: p, deviceLabel: 'Dexter full frontend' }, token: null });
  if (r.body && r.body.mfaRequired) toast(`${e} has MFA on — finish on Sign-in & security (live).`, 5000);
  else if (r.status !== 200) toast(`Live sign-in for ${e}: ${r.status} ${(r.body || {}).code || ''} — check DEMO passwords in live.js`, 6000);
}
function setMode(live) {
  state.live = live; try { localStorage.setItem('dexter.mode', live ? 'live' : 'mock'); } catch { }
  LIVE.cache = {}; REGISTRY.forEach((r) => (r.hits = 0));
  if (live && location.protocol === 'file:') toast('Live mode needs the page served by the trail nginx: ./scripts/run.sh trail → http://localhost:8099/app/', 7000);
  if (live) ensureLiveLogin(); render();
}
/* data helpers that work in both modes (mock reads went through DB before) */
const cached = (k, fn) => (LIVE.cache[k] !== undefined ? LIVE.cache[k] : (LIVE.cache[k] = fn()));
const dropCache = (...ks) => ks.forEach((k) => delete LIVE.cache[k]);
function CATS() { if (!state.live) return DB.ledger.categories.filter((c) => !c.accountId || c.accountId === curAcct()); return cached('cats:' + state.persona, () => ((call('GET', '/v1/categories', { quiet: true }).body || {}).data || [])); }
function myCards() { return ((call('GET', '/v1/cards?limit=100', { quiet: true }).body || {}).data || []); }
function catalogCards() { return cached('catalog', () => ((call('GET', '/v1/catalog/cards?limit=100', { token: null, quiet: true }).body || {}).data || [])); }
const mockOnly = (what) => state.live ? `<div class="note warn"><b>${what} is mock-only.</b> It instruments the in-browser mock (row-level touch tracing, the event bus, seeded ids). Against the live stack use the API inspector, the services' logs, Kafka UI / Grafana, or <span class="mono">./scripts/api-smoke.sh</span>. <button class="btn sm" onclick="setMode(false);$('#mode').value='mock'">Switch to mock</button></div>` : '';
