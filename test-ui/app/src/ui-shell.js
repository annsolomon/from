/* ---------- shell: nav, persona, API inspector, router ---------- */
const NAV = [
  ['Overview', [['home', 'Home'], ['logic', 'Answers: your 3 questions'], ['recalc', 'LTREE recalculation lab']]],
  ['Money', [['expenses', 'Expenses'], ['insights', 'Calendar · summary · rules'], ['budgets', 'Budgets & planning'], ['cards', 'My cards & rewards'], ['statements', 'Statement upload'], ['emi', 'EMI center'], ['calc', 'Calculators (12)']]],
  ['Marketplaces', [['catalog', 'Card marketplace'], ['deals', 'Deals & offers marketplace']]],
  ['Intelligence', [['reco', 'Recommendations & wallet']]],
  ['Account', [['auth', 'Sign-in & security'], ['billing', 'Plans & billing'], ['inbox', 'Notifications & privacy']]],
  ['Admin console', [['admin-identity', 'Admins & accounts'], ['admin-catalog', 'Catalog admin'], ['admin-ingest', 'Ingestion & review'], ['admin-commerce', 'Commerce admin'], ['admin-audit', 'Audit & activity']]],
  ['Platform', [['platform', 'Gateway · events · run-all'], ['index', 'Endpoint index']]],
];
const SCREENS = {};
const TITLES = Object.fromEntries(NAV.flatMap(([, l]) => l));
function shell() {
  document.body.innerHTML = `<div id="app"><nav id="side"><div class="brand"><i></i>Dexter <span class="badge" style="margin-left:auto">v2 · mock</span></div>
  ${NAV.map(([g, l]) => `<div class="grp">${g}</div>` + l.map(([k, t]) => `<a class="nav" data-go="${k}">${t}<small data-cnt="${k}"></small></a>`).join('')).join('')}
  <div class="grp">Data</div><div style="padding:0 8px;font-size:11.5px;color:#7d828c;line-height:1.5">Seed: repo <span class="mono">seed/data/v1</span> fixtures (MOCK banks). Calculators: exact ports of <span class="mono">libs/portfolio-calc</span>, verified 1,276/1,276 against the Java engines.</div></nav>
  <main id="main"><div id="top"><button class="btn sm hamb" id="hamb">☰</button><div class="title" id="ttl"></div>
  <select id="mode" title="Mock = in-browser backend · Live = the real stack via /api"><option value="mock">Mock data</option><option value="live">Live backend (/api)</option></select><span class="small mute" id="clock"></span>
  <select id="persona" title="Who is calling the API"><option value="asha">Asha · PREMIUM (user)</option><option value="ravi">Ravi · FREE (user, MFA)</option><option value="admin">Admin · SUPER_ADMIN</option><option value="anon">Signed out (anonymous)</option></select>
  <button class="btn sm p" id="insp">API inspector <span id="ncalls" class="pill" style="background:#333;color:#fff">0</span></button></div>
  <div id="content"></div></main></div>
  <aside id="drawer"><header><b>API inspector</b><span class="small" style="color:#8a8f99">every call the UI makes, as the gateway sees it</span><span class="sp"></span><input id="logf" placeholder="filter" style="background:#1b1e26;border-color:#333;color:#ddd;width:110px"><button class="btn sm" id="logc">Clear</button><button class="btn sm" id="logx">✕</button></header><div class="log" id="log"></div></aside><div id="toast"></div>`;
  on('[data-go]', 'click', (e, el) => go(el.dataset.go));
  $('#persona').value = state.persona; $('#mode').value = state.live ? 'live' : 'mock'; $('#mode').onchange = (e) => setMode(e.target.value === 'live');
  $('#persona').onchange = (e) => { state.persona = e.target.value; ensureLiveLogin(); toast('Calling the API as ' + e.target.selectedOptions[0].text); render(); };
  $('#insp').onclick = () => $('#drawer').classList.toggle('open');
  $('#logx').onclick = () => $('#drawer').classList.remove('open');
  $('#logc').onclick = () => { LOG.length = 0; renderLog(); };
  $('#logf').oninput = renderLog;
  $('#hamb').onclick = () => $('#side').classList.toggle('open');
  document.addEventListener('click', (e) => { const ep = e.target.closest('[data-ep]'); if (ep) { $('#drawer').classList.add('open'); $('#logf').value = ep.dataset.ep.split(' ')[1].replace(/\{[^}]+\}/g, '').split(':')[0]; renderLog(); } });
  window.addEventListener('hashchange', () => { const h = location.hash.slice(1); if (h && h !== state.screen && SCREENS[h]) { state.screen = h; render(); } });
  setInterval(() => { const c = $('#clock'); if (c) c.textContent = state.live ? 'LIVE · ' + location.host : 'Mock clock ' + fmtDate(nowIso()) + ' ' + fmtTime(nowIso()) + ' IST'; }, 1000);
}
function go(k) { state.screen = k; try { history.replaceState(null, '', '#' + k); } catch { } $('#side').classList.remove('open'); render(); }
function render() {
  $$('#side a.nav').forEach((a) => a.classList.toggle('on', a.dataset.go === state.screen));
  $('#ttl').textContent = (TITLES[state.screen] || '') + (state.live ? ' · LIVE' : '');
  const root = $('#content'); root.scrollTop = 0;
  try { SCREENS[state.screen](root); } catch (e) { console.error(e); root.innerHTML = `<div class="note bad">Render error: ${esc(e.message)}${state.live ? '<br>Live mode: a response did not have the shape this screen expects (or the call failed). Open the API inspector to see the raw answer, and tell me which endpoint — the mock was built from the controllers\' records but has not been run against your stack.' : ''}</div>`; }
}
function renderLog() {
  const el = document.getElementById('log'); if (!el) return;
  const nc = document.getElementById('ncalls'); if (nc) nc.textContent = LOG.length;
  const f = (document.getElementById('logf') || {}).value || '';
  el.innerHTML = LOG.filter((l) => !f || l.url.includes(f)).slice(0, 120).map((l) => `<details class="call"><summary><span class="m ${l.method}">${l.method}</span><span style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(l.url)}</span><span style="color:#777">${l.svc}</span><span class="st s${String(l.resp.status)[0]}">${l.resp.status}</span></summary>
  <pre>${esc(Object.entries(l.reqHeaders).map(([k, v]) => k + ': ' + v).join('\n'))}${l.body ? '\n\n' + json(l.body) : ''}</pre><pre style="color:#bfe">${esc(Object.entries(l.resp.headers || {}).map(([k, v]) => k + ': ' + v).join('\n'))}\n\n${l.resp.body == null ? '(no body)' : json(l.resp.body)}</pre></details>`).join('');
}
/** call helpers: show problem details as toasts */
function call(m, url, opt = {}) { const r = api(m, url, opt); if (r.status >= 400 && !opt.quiet) toast(`${r.status} ${r.body.code}: ${r.body.detail || ''}`, 3500); return r; }
const acall = (m, url, opt = {}) => call(m, url, { ...opt, token: state.live ? LIVE.tokens.admin || null : state.tokens.admin });
const idem = () => ({ 'Idempotency-Key': uuid7() });
const userOnly = () => (state.persona === 'asha' || state.persona === 'ravi') ? (state.live && !LIVE.tokens[state.persona] ? `<div class="note warn">Live mode: no session for ${state.persona} yet — sign in on <a href="#auth">Sign-in & security</a> (MFA accounts finish there).</div>` : '') : `<div class="note warn">You are calling as <b>${state.persona}</b>. User endpoints answer <b>401 UNAUTHENTICATED</b> — that's the gateway's JWT pre-check working. Switch the persona (top right) to Asha or Ravi to see data.</div>`;
const curAcct = () => (state.persona === 'ravi' ? RAVI : ASHA);
const tabState = (k, d) => (state.sub[k] = state.sub[k] || d);
function bindTabs(root, k, rerender) { on('[data-tabs="' + k + '"] [data-tab]', 'click', (e, el) => { state.sub[k] = el.dataset.tab; rerender(); }, root); }
function catOptions(sel, allowNone = true) { return (allowNone ? '<option value="">(all / total)</option>' : '') + CATS().slice().sort((a, b) => (a.path < b.path ? -1 : 1)).map((c) => `<option value="${c.path}" ${c.path === sel ? 'selected' : ''}>${'  '.repeat(c.path.split('.').length - 1)}${c.path.includes('.') ? '└ ' : ''}${esc(c.name)} · ${c.path}</option>`).join(''); }
function rootDot(p) { return `<span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:${ROOT_COLORS[rootOf(p)] || '#999'};margin-right:5px"></span>`; }
function jsonBox(o, h = 260) { return `<pre class="mono" style="background:#f7f5f0;border:1px solid var(--line);border-radius:8px;padding:8px;max-height:${h}px;overflow:auto;margin:0;white-space:pre-wrap">${json(o)}</pre>`; }
function badge(kind, text) { return `<span class="pill ${kind === 'in' ? 'inrepo' : kind === 'no' ? 'notrepo' : 'partial'}">${text}</span>`; }
