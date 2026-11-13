/* ---------- core helpers ---------- */
const NOW_ISO = '2026-09-30T14:30:00.000Z'; // mock clock: 30 Sep 2026, 20:00 IST (seed window ends Sep 2026)
let clockOffsetMs = 0;
const now = () => new Date(Date.parse(NOW_ISO) + clockOffsetMs);
const nowIso = () => now().toISOString();
const IST = 330 * 60000;
const istDay = (iso) => new Date(Date.parse(iso) + IST).toISOString().slice(0, 10);
const today = () => istDay(nowIso());
const addDays = (d, n) => new Date(Date.parse(d + 'T00:00:00Z') + n * 86400000).toISOString().slice(0, 10);
const daysBetween = (a, b) => Math.round((Date.parse(b + 'T00:00:00Z') - Date.parse(a + 'T00:00:00Z')) / 86400000);
const monthOf = (d) => d.slice(0, 7);
const monthStart = (d) => d.slice(0, 8) + '01';
const monthEnd = (d) => { const [y, m] = d.split('-').map(Number); return new Date(Date.UTC(y, m, 0)).toISOString().slice(0, 10); };
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
function rs(p, opt = {}) {
  if (p == null) return '—';
  const v = p / 100, whole = Number.isInteger(v) || opt.whole;
  return (v < 0 ? '−₹' : '₹') + Math.abs(opt.whole ? Math.round(v) : v).toLocaleString('en-IN', { minimumFractionDigits: whole ? 0 : 2, maximumFractionDigits: whole ? 0 : 2 });
}
const rsk = (p) => { const v = Math.abs(p) / 100; const s = p < 0 ? '−₹' : '₹'; return v >= 1e7 ? s + (v / 1e7).toFixed(2) + ' Cr' : v >= 1e5 ? s + (v / 1e5).toFixed(2) + ' L' : v >= 1e3 ? s + (v / 1e3).toFixed(1) + 'k' : s + v.toFixed(0); };
const pct = (bps, d = 2) => (bps / 100).toFixed(d).replace(/\.?0+$/, '') + '%';
const fmtDate = (iso) => { if (!iso) return '—'; const d = iso.length === 10 ? iso : istDay(iso); const [y, m, dd] = d.split('-'); return `${+dd} ${'Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec'.split(' ')[m - 1]} ${y}`; };
const fmtTime = (iso) => new Date(Date.parse(iso) + IST).toISOString().slice(11, 16);
let __seq = 0;
function uuid7() {
  const t = now().getTime() + (++__seq);
  const h = t.toString(16).padStart(12, '0');
  const r = () => Math.floor(Math.random() * 65536).toString(16).padStart(4, '0');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-7${r().slice(1)}-${(8 + Math.floor(Math.random() * 4)).toString(16)}${r().slice(1)}-${r()}${r()}${r()}`;
}
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
function on(sel, ev, fn, root = document) { $$(sel, root).forEach((el) => el.addEventListener(ev, (e) => fn(e, el))); }
function toast(msg, ms = 2400) { const t = $('#toast'); t.textContent = msg; t.classList.add('on'); clearTimeout(t._h); t._h = setTimeout(() => t.classList.remove('on'), ms); }
function modal(title, body, bind) {
  const bg = document.createElement('div'); bg.className = 'modal-bg';
  bg.innerHTML = `<div class="modal"><div class="row"><h3 style="margin:0">${title}</h3><span class="sp"></span><button class="btn sm" data-x>Close</button></div><div class="mbody" style="margin-top:10px">${body}</div></div>`;
  document.body.appendChild(bg);
  const close = () => bg.remove();
  bg.addEventListener('click', (e) => { if (e.target === bg || e.target.hasAttribute('data-x')) close(); });
  if (bind) bind(bg, close);
  return close;
}
const json = (o) => esc(JSON.stringify(o, null, 2));
const epTag = (m, p) => `<span class="ep" data-ep="${esc(m + ' ' + p)}" title="Show in API inspector">${m} ${esc(p)}</span>`;
function bar(used, limit, thresholds = []) {
  const p = limit ? Math.min(100, used / limit * 100) : 0;
  const cls = p >= 100 ? 'x' : p >= 80 ? 'w' : '';
  return `<div class="bar"><i class="${cls}" style="width:${p}%"></i>${thresholds.map((t) => `<span class="t" style="left:${Math.min(t, 100)}%"></span>`).join('')}</div>`;
}
function tabs(id, names, cur) { return `<div class="tabs" data-tabs="${id}">${names.map(([k, l]) => `<button data-tab="${k}" class="${k === cur ? 'on' : ''}">${l}</button>`).join('')}</div>`; }
function table(cols, rows, opt = {}) {
  return `<div class="tw" style="${opt.max ? 'max-height:' + opt.max : ''}"><table><thead><tr>${cols.map((c) => `<th class="${c.n ? 'n' : ''}">${c.l}</th>`).join('')}</tr></thead><tbody>${rows.length ? rows.map((r, i) =>
    `<tr class="${opt.click ? 'click' : ''} ${opt.hl && opt.hl(r) ? 'hl' : ''}" data-i="${i}" ${opt.rowAttr ? opt.rowAttr(r) : ''}>${cols.map((c) => `<td class="${c.n ? 'n' : ''}">${c.f ? c.f(r, i) : esc(r[c.k])}</td>`).join('')}</tr>`).join('') :
    `<tr><td colspan="${cols.length}" class="mute">${opt.empty || 'Nothing here yet.'}</td></tr>`}</tbody></table></div>`;
}
const P2R = (s) => Math.round(parseFloat(String(s).replace(/[₹,\s]/g, '')) * 100); // rupee text -> paise
const v = (sel, root = document) => { const e = $(sel, root); return e ? e.value : undefined; };
const state = { persona: 'asha', screen: 'home', tokens: {}, sub: {} };
const ROOT_COLORS = { food: '#e8772e', travel: '#2c62c9', fuel: '#b7791f', shopping: '#9b4dca', utilities: '#0f8b7d', entertainment: '#d6336c', health: '#14864d', education: '#5f6f86', housing: '#6c5b3b', finance: '#495057', transport: '#3a86ff', general: '#8a8f99', personal: '#c08552', groceries: '#2b9348' };
const rootOf = (p) => (p || 'general').split('.')[0];
const isUnder = (path, anc) => !anc || path === anc || (path || '').startsWith(anc + '.');
const cardGrad = (slug = '') => { const h = [...slug].reduce((a, c) => (a * 31 + c.charCodeAt(0)) % 360, 7); return `linear-gradient(135deg,hsl(${h} 45% 28%),hsl(${(h + 40) % 360} 55% 42%))`; };
