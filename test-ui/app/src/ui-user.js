/* ================= HOME ================= */
SCREENS.home = (root) => {
  if (!['asha', 'ravi'].includes(state.persona)) { root.innerHTML = userOnly() + `<div class="card"><h3>Dexter v2 — full frontend over the 10-service API</h3><p>Pick a user persona to see the app. Admin screens are in the sidebar under <b>Admin console</b>.</p></div>`; return; }
  const me = call('GET', '/v1/me').body; const st = call('GET', '/v1/budgets/status').body.data || [];
  const cal = call('GET', '/v1/calendar?month=' + monthOf(today())).body; const cal2 = call('GET', '/v1/calendar?month=' + monthOf(addDays(monthEnd(today()), 1))).body; const sum = call('GET', `/v1/summary?from=${monthStart(today())}&to=${today()}&groupBy=category`).body;
  const rw = call('GET', '/v1/rewards/balance').body; const emi = call('GET', '/v1/emi-plans').body.data || [];
  const rc = call('GET', '/v1/recommendations').body; const nt = call('GET', '/v1/notifications?limit=5').body;
  const ad = call('GET', '/v1/ads/serve?placement=home').body; const cards = call('GET', '/v1/cards').body.data || [];
  const emiOut = emi.filter((p) => p.status === 'active').reduce((a, p) => a + (((call('GET', `/v1/emi-plans/${p.id}/schedule?limit=100`, { quiet: true }).body || {}).data) || []).filter((i) => i.status === 'due' && i.dueDate >= today()).reduce((x, i) => x + i.principalPaise, 0), 0);
  const upcoming = [...(cal.days || []), ...(cal2.days || [])].filter((d) => d.day >= today() && d.day <= addDays(today(), 30)).flatMap((d) => d.upcoming.map((u) => ({ ...u, day: d.day }))); const upSum = upcoming.reduce((a, u) => a + u.amountPaise, 0);
  root.innerHTML = `
  <div class="row" style="margin-bottom:10px"><h2 style="margin:0">Hi ${esc(me.displayName.split(' ')[0])}</h2><span class="pill acc">${me.tier}</span>${me.mfaEnrolled ? '<span class="pill ok">MFA on</span>' : '<span class="pill warn">MFA off</span>'}<span class="sp"></span>${epTag('GET', '/v1/me')}</div>
  <div class="grid g4">
   <div class="card kpi"><div class="l">Spent this month</div><div class="v">${rs(sum.totalPaise, { whole: 1 })}</div><div class="s">${cal.txnCount} transactions · ${epTag('GET', '/v1/summary')}</div></div>
   <div class="card kpi"><div class="l">Due in the next 30 days</div><div class="v">${rs(upSum, { whole: 1 })}</div><div class="s">${upcoming.length} items · ${epTag('GET', '/v1/calendar')}</div></div>
   <div class="card kpi"><div class="l">Rewards balance</div><div class="v">${rw.points.toLocaleString('en-IN')} pts</div><div class="s">worth ${rs(rw.valuePaise, { whole: 1 })} · ${rw.expiringSoon} expiring ≤60d</div></div>
   <div class="card kpi"><div class="l">EMI principal outstanding</div><div class="v">${rs(emiOut, { whole: 1 })}</div><div class="s">${emi.filter((p) => p.status === 'active').length} active plans · <a href="#emi" data-go="emi">EMI center →</a></div></div>
  </div>
  <div class="grid g2">
   <div class="card"><h3>Budgets this period <span class="sp"></span>${epTag('GET', '/v1/budgets/status')}</h3>
    ${st.map((s) => `<div style="margin:8px 0"><div class="row small"><b>${esc(s.budget.name)}</b><span class="mute mono">${s.budget.categoryPath || 'total'}</span><span class="sp"></span>${rs(s.current.spentPaise, { whole: 1 })} / ${rs(s.current.limitPaise, { whole: 1 })}</div>${bar(s.current.spentPaise, s.current.limitPaise, s.budget.alertThresholds)}<div class="small mute">Projected ${rs(s.current.projectedSpendPaise, { whole: 1 })} ${s.current.projectedOverspendPaise ? `<span class="pill bad">over by ${rs(s.current.projectedOverspendPaise, { whole: 1 })}</span>` : ''}</div></div>`).join('')}
   </div>
   <div class="card"><h3>Best cards for you <span class="sp"></span>${epTag('GET', '/v1/recommendations')}</h3>
    ${(rc.recommended || []).slice(0, 4).map((r, i) => `<div class="row" style="padding:6px 0;border-bottom:1px solid var(--line)"><b>${i + 1}.</b> ${esc(r.name.replace('MOCK_', ''))} ${r.held ? '<span class="pill ok">held</span>' : ''}<span class="sp"></span><span class="b">${rs(r.annualSavingsPaise, { whole: 1 })}/yr</span><span class="pill">${pct(r.scoreBps, 1)} score</span></div>`).join('') || '<div class="mute">No spend yet.</div>'}
    <div class="small mute" style="margin-top:6px">Cached; recomputed only when the state hash of your inputs changes · computed ${fmtTime(rc.computedAt || nowIso())}</div>
   </div>
  </div>
  <div class="grid g3">
   <div class="card"><h3>Where the money went</h3>${(sum.groups || []).slice(0, 7).map((g) => `<div class="row small" style="margin:4px 0">${rootDot(g.key)}${esc(g.label)}<span class="sp"></span>${rs(g.totalPaise, { whole: 1 })} <span class="mute">${pct(g.shareBps, 0)}</span></div>${bar(g.shareBps, 10000)}`).join('')}</div>
   <div class="card"><h3>Upcoming</h3>${upcoming.slice(0, 7).map((u) => `<div class="row small" style="margin:5px 0"><span class="pill ${u.kind === 'card_due' ? 'warn' : u.kind === 'card_emi' ? 'acc' : 'info'}">${u.kind}</span>${esc(u.label)}<span class="sp"></span>${fmtDate(u.day)} · <b>${rs(u.amountPaise, { whole: 1 })}</b></div>`).join('') || '<div class="mute small">Nothing due.</div>'}</div>
   <div class="card"><h3>Inbox <span class="pill">${nt.unread} unread</span><span class="sp"></span>${epTag('GET', '/v1/notifications')}</h3>${(nt.data || []).map((n) => `<div class="small" style="margin:6px 0;${n.readAt ? 'opacity:.6' : ''}"><span class="pill">${n.category}</span> <b>${esc(n.title)}</b><div class="mute">${esc(n.body)}</div></div>`).join('')}</div>
  </div>
  <div class="grid g2"><div class="card"><h3>Your cards</h3><div class="grid g3">${cards.map((c) => `<div class="creditcard" style="background:${cardGrad(c.productSlug)}"><div class="nm">${esc(c.nickname)}</div><div class="num">•••• ${c.last4}</div><div class="small">Due ${fmtDate(c.currentCycle.dueDate)}</div><div class="net">${c.network}</div></div>`).join('')}</div></div>
  <div class="card"><h3>Ad slot <span class="sp"></span>${epTag('GET', '/v1/ads/serve')}</h3>${ad.ad ? `<div class="dealcard"><span class="pill">Sponsored · ${esc(ad.ad.advertiser)}</span><b>${esc(ad.ad.headline)}</b><span class="small">${esc(ad.ad.body)}</span><span class="mono small mute">${esc(ad.ad.clickPath.slice(0, 40))}…</span></div>` : `<div class="note ok">No ad: <b>${ad.reason}</b>${ad.reason === 'AD_FREE' ? ' — your plan\'s <span class="mono">adFree</span> entitlement (projected from billing.entitlements.changed)' : ''}</div>`}</div></div>`;
  on('[data-go]', 'click', (e, el) => { e.preventDefault(); go(el.dataset.go); }, root);
};

/* ================= EXPENSES ================= */
const pager = { buffer: [], shown: 0, cursor: null, hasMore: true, inflight: false, filters: {}, calls: [] };
function pagerInit() {
  const qs = new URLSearchParams(Object.fromEntries(Object.entries(pager.filters).filter(([, v]) => v))).toString();
  const r = call('GET', '/v1/expenses?limit=50' + (qs ? '&' + qs : ''));
  if (r.status !== 200) { pager.buffer = []; pager.shown = 0; return; }
  pager.buffer = r.body.data; pager.cursor = r.body.page.nextCursor; pager.hasMore = r.body.page.hasMore; pager.shown = Math.min(25, pager.buffer.length); pager.calls = [{ limit: 50, got: r.body.data.length }];
}
function pagerVisible(i) { // 1-based index of the last visible item; MASTER_PLAN §4.4 window
  if (i >= pager.shown - 1) {
    if (pager.shown < pager.buffer.length) pager.shown = Math.min(pager.shown + 25, pager.buffer.length);
    if (pager.hasMore && !pager.inflight) {
      pager.inflight = true; const qs = new URLSearchParams(Object.fromEntries(Object.entries(pager.filters).filter(([, v]) => v))).toString();
      setTimeout(() => { const r = call('GET', '/v1/expenses?limit=25&cursor=' + encodeURIComponent(pager.cursor) + (qs ? '&' + qs : '')); if (r.status === 200) { const ids = new Set(pager.buffer.map((x) => x.id)); pager.buffer.push(...r.body.data.filter((x) => !ids.has(x.id))); pager.cursor = r.body.page.nextCursor; pager.hasMore = r.body.page.hasMore; pager.calls.push({ limit: 25, got: r.body.data.length }); } pager.inflight = false; drawPager(); }, 250);
    }
    drawPager();
  }
}
function drawPager() {
  const el = $('#pagerDbg'); if (!el) return;
  el.innerHTML = `buffer <b>${pager.buffer.length}</b> · shown <b>${pager.shown}</b> · hasMore <b>${pager.hasMore}</b> · inflight <b>${pager.inflight}</b> · calls ${pager.calls.map((c) => `limit=${c.limit}→${c.got}`).join(', ')}`;
  const tb = $('#expRows'); if (!tb) return;
  const cards = cached('cardmap:' + state.persona, () => Object.fromEntries(myCards().map((c) => [c.id, c])));
  tb.innerHTML = pager.buffer.slice(0, pager.shown).map((e, i) => `<tr class="click" data-id="${e.id}"><td class="mute">${i + 1}</td><td>${fmtDate(e.occurredAt)} <span class="mute small">${fmtTime(e.occurredAt)}</span></td><td>${esc(e.merchant)}</td><td>${rootDot(e.categoryPath)}<span class="mono">${e.categoryPath}</span> <span class="pill ${e.categorySource === 'user' ? 'info' : e.resolutionConfidence >= 900 ? 'ok' : e.resolutionConfidence >= 450 ? 'warn' : 'bad'}">${e.categorySource} ${e.resolutionConfidence ?? ''}</span></td><td>${e.paymentMethod}${e.userCardId && cards[e.userCardId] ? ' ··' + cards[e.userCardId].last4 : ''}</td><td class="n b">${rs(e.amountPaise)}</td></tr>`).join('') + (pager.inflight && pager.shown >= pager.buffer.length ? '<tr><td colspan="6" class="mute">Loading 51–75…</td></tr>' : '');
  on('#expRows tr[data-id]', 'click', (e, el) => expenseModal(el.dataset.id));
}
let lastIdemKey = null, lastIdemBody = null;
function merchantInput(id, onPick) {
  return { html: `<div class="ac" id="${id}w"><input id="${id}" placeholder="Type a merchant — e.g. swig, zomato, UPI-UBER…" autocomplete="off" style="width:100%"><div class="list" style="display:none"></div></div>`,
    bind(root) { const inp = $('#' + id, root), list = $('#' + id + 'w .list', root);
      inp.addEventListener('input', () => { if (inp.value.length < 2) { list.style.display = 'none'; return; } const r = call('GET', '/v1/merchants/autocomplete?q=' + encodeURIComponent(inp.value), { quiet: true }); const d = (r.body && r.body.data) || []; list.innerHTML = d.map((m) => `<div data-m='${esc(JSON.stringify(m))}'><span>${esc(m.name)}</span><span class="mono mute">${m.category} · ${m.score}</span></div>`).join(''); list.style.display = d.length ? 'block' : 'none'; $$('div[data-m]', list).forEach((x) => x.onclick = () => { const m = JSON.parse(x.dataset.m); inp.value = m.name; list.style.display = 'none'; onPick(m, inp.value); }); });
      inp.addEventListener('blur', () => setTimeout(() => { list.style.display = 'none'; if (inp.value.length >= 2) onPick(null, inp.value); }, 180)); } };
}
SCREENS.expenses = (root) => {
  const t = tabState('exp', 'list');
  root.innerHTML = userOnly() + tabs('exp', [['list', 'List (keyset window)'], ['add', 'Add · resolver · idempotency'], ['search', 'Search'], ['bulk', 'Bulk'], ['daily', 'Daily totals']], t) + '<div id="expBody"></div>';
  bindTabs(root, 'exp', () => SCREENS.expenses(root)); const B = $('#expBody', root);
  const cards = (call('GET', '/v1/cards', { quiet: true }).body || {}).data || [];
  if (t === 'list') {
    B.innerHTML = `<div class="card"><div class="row end">
      <label class="f">From<input type="date" id="fFrom" value="${pager.filters.from || ''}"></label><label class="f">To<input type="date" id="fTo" value="${pager.filters.to || ''}"></label>
      <label class="f">Category (subtree, ltree &lt;@)<select id="fCat">${catOptions(pager.filters.category)}</select></label>
      <label class="f">Payment<select id="fPm"><option value="">any</option>${['card', 'upi', 'cash', 'netbanking', 'wallet', 'other'].map((p) => `<option ${pager.filters.paymentMethod === p ? 'selected' : ''}>${p}</option>`).join('')}</select></label>
      <label class="f">Card<select id="fCard"><option value="">any</option>${cards.map((c) => `<option value="${c.id}" ${pager.filters.cardId === c.id ? 'selected' : ''}>${esc(c.nickname)} ··${c.last4}</option>`).join('')}</select></label>
      <label class="f">Min ₹<input id="fMin" style="width:80px" value="${pager.filters.minPaise ? pager.filters.minPaise / 100 : ''}"></label><label class="f">Max ₹<input id="fMax" style="width:80px" value="${pager.filters.maxPaise ? pager.filters.maxPaise / 100 : ''}"></label>
      <button class="btn p" id="fGo">Apply</button><button class="btn" id="fReset">Reset</button></div>
      <div class="note small">MASTER_PLAN §4.4: first call <span class="mono">limit=50</span>, show 1–25; when item 24 is visible show 26–50 instantly and fetch 51–75 in the background (<span class="mono">limit=25&cursor=…</span>). The cursor is signed and bound to the filters — reuse it with other filters → <span class="mono">400 CURSOR_FILTER_MISMATCH</span>. <button class="btn sm" id="badCur">Try a mismatched cursor</button></div>
      <div class="mono small" id="pagerDbg" style="margin:6px 0"></div>
      <div class="tw" id="expScroll" style="max-height:520px"><table><thead><tr><th>#</th><th>When (IST)</th><th>Merchant</th><th>Category · source · confidence</th><th>Paid via</th><th class="n">Amount</th></tr></thead><tbody id="expRows"></tbody></table></div></div>`;
    pagerInit(); drawPager();
    $('#expScroll', root).addEventListener('scroll', (e) => { const sc = e.target; const rows = $$('#expRows tr', root); const bottom = sc.scrollTop + sc.clientHeight; let last = 0; rows.forEach((r, i) => { if (r.offsetTop < bottom) last = i + 1; }); pagerVisible(last); });
    $('#fGo', root).onclick = () => { pager.filters = { from: v('#fFrom'), to: v('#fTo'), category: v('#fCat'), paymentMethod: v('#fPm'), cardId: v('#fCard'), minPaise: v('#fMin') ? P2R(v('#fMin')) : '', maxPaise: v('#fMax') ? P2R(v('#fMax')) : '' }; pagerInit(); drawPager(); };
    $('#fReset', root).onclick = () => { pager.filters = {}; SCREENS.expenses(root); };
    $('#badCur', root).onclick = () => { if (!pager.cursor) return toast('Scroll until a cursor exists'); call('GET', '/v1/expenses?limit=25&category=travel&cursor=' + encodeURIComponent(pager.cursor)); $('#drawer').classList.add('open'); };
  }
  if (t === 'add') {
    const mi = merchantInput('mIn', (m, text) => { const r = call('POST', '/v1/merchants/resolve', { body: { merchant: text } }).body; $('#resolved', root).innerHTML = `<div class="kv"><b>normalized</b><span class="mono">${esc(r.normalized)}</span><b>method</b><span><span class="pill ${r.method === 'alias' || r.method === 'rule' ? 'ok' : r.method === 'trigram' ? 'warn' : 'bad'}">${r.method}</span></span><b>merchant</b><span>${esc(r.merchant)}</span><b>category</b><span class="mono">${r.category}</span><b>confidence</b><span>${r.confidence} / 1000</span></div>`; if (!$('#aCat', root).value) $('#aCatHint', root).textContent = 'resolver will use ' + r.category; });
    B.innerHTML = `<div class="split"><div class="card"><h3>New expense ${epTag('POST', '/v1/expenses')}</h3>
      <div class="grid g2"><label class="f">Amount (₹)<input id="aAmt" value="459"></label><label class="f">When<input type="datetime-local" id="aWhen" value="${new Date(now().getTime() + IST).toISOString().slice(0, 16)}"></label></div>
      <label class="f" style="margin-top:8px">Merchant (autocomplete ${epTag('GET', '/v1/merchants/autocomplete')})${mi.html}</label>
      <div class="grid g2" style="margin-top:8px"><label class="f">Category override (blank = resolver)<select id="aCat">${catOptions('', true).replace('(all / total)', '(let the resolver decide)')}</select><span class="small mute" id="aCatHint"></span></label>
      <label class="f">Payment<select id="aPm">${['card', 'upi', 'cash', 'netbanking', 'wallet', 'other'].map((p) => `<option>${p}</option>`).join('')}</select></label></div>
      <div class="grid g2" style="margin-top:8px"><label class="f">Card<select id="aCard"><option value="">—</option>${cards.map((c) => `<option value="${c.id}">${esc(c.nickname)} ··${c.last4}</option>`).join('')}</select></label><label class="f">Note<input id="aNote" maxlength="500"></label></div>
      <div class="row" style="margin-top:10px"><button class="btn a" id="aSave">Save (new Idempotency-Key)</button><button class="btn" id="aReplay" ${lastIdemKey ? '' : 'disabled'}>Retry same key (replay)</button><button class="btn" id="aReuse" ${lastIdemKey ? '' : 'disabled'}>Same key, different body → 422</button><button class="btn d" id="aNoKey">No key → 400</button></div>
      <div id="aOut" style="margin-top:10px"></div></div>
      <div class="card"><h3>Resolver ${epTag('POST', '/v1/merchants/resolve')}</h3><div class="small mute">Pipeline (P08): normalize (lowercase, strip UPI handles, *, POS prefixes, city words) → your rules → exact alias → trigram similarity ≥ 0.45 → fallback <span class="mono">general</span>. Confidence 0–1000 is always populated.</div><div id="resolved" style="margin-top:8px" class="mute small">Type a merchant…</div>
      <h3 style="margin-top:14px">What one write touches</h3><div id="aTouch" class="small mute">${state.live ? 'Row-level touch tracing is mock-only; live, watch the ledger/planning/reco logs or Grafana.' : 'Save an expense to see every row the platform updates — and nothing else.'}</div></div></div>`;
    mi.bind(root);
    const body = () => { const b = { amountPaise: P2R(v('#aAmt')), occurredAt: new Date(Date.parse(v('#aWhen')) - IST).toISOString(), merchant: v('#mIn'), paymentMethod: v('#aPm') }; if (v('#aCat')) b.categoryPath = v('#aCat'); if (v('#aCard')) b.userCardId = v('#aCard'); if (v('#aNote')) b.note = v('#aNote'); return b; };
    const show = (r) => { $('#aOut', root).innerHTML = `<div class="note ${r.status < 300 ? 'ok' : 'bad'}"><b>${r.status}</b> ${r.headers['Idempotent-Replayed'] ? '<span class="pill info">Idempotent-Replayed: true — same response, no second row</span>' : ''} ${r.body && r.body.code ? r.body.code : ''}</div>` + jsonBox(r.body, 200); };
    $('#aSave', root).onclick = () => { TOUCH.length = 0; lastIdemKey = uuid7(); lastIdemBody = body(); const r = call('POST', '/v1/expenses', { body: lastIdemBody, headers: { 'Idempotency-Key': lastIdemKey } }); show(r); $('#aReplay', root).disabled = $('#aReuse', root).disabled = false; $('#aTouch', root).innerHTML = touchList(); };
    $('#aReplay', root).onclick = () => { TOUCH.length = 0; show(call('POST', '/v1/expenses', { body: lastIdemBody, headers: { 'Idempotency-Key': lastIdemKey } })); $('#aTouch', root).innerHTML = touchList() || '<div class="note ok">Replay touched nothing.</div>'; };
    $('#aReuse', root).onclick = () => show(call('POST', '/v1/expenses', { body: { ...lastIdemBody, amountPaise: lastIdemBody.amountPaise + 100 }, headers: { 'Idempotency-Key': lastIdemKey } }));
    $('#aNoKey', root).onclick = () => show(call('POST', '/v1/expenses', { body: body(), noAutoIdem: true }));
  }
  if (t === 'search') {
    B.innerHTML = `<div class="card"><div class="row"><input id="sQ" placeholder="swiggy, uber, a note…" value="${esc(state.sub.sq || '')}"><button class="btn p" id="sGo">Search</button>${epTag('GET', '/v1/expenses/search')}<span class="small mute">Trigram on merchant + note within the account; bounded to 12 months (tenant + date btree first, trigram recheck).</span></div><div id="sOut" style="margin-top:10px"></div></div>`;
    const run = () => { state.sub.sq = v('#sQ'); const r = call('GET', '/v1/expenses/search?q=' + encodeURIComponent(v('#sQ')) + '&limit=50'); $('#sOut', root).innerHTML = r.status === 200 ? table([{ l: 'When', f: (e) => fmtDate(e.occurredAt) }, { l: 'Merchant', k: 'merchant' }, { l: 'Category', f: (e) => `<span class="mono">${e.categoryPath}</span>` }, { l: 'Note', f: (e) => esc(e.note || '') }, { l: 'Amount', n: 1, f: (e) => rs(e.amountPaise) }], r.body.data, { click: 1, rowAttr: (e) => `data-id="${e.id}"` }) + `<div class="small mute">hasMore ${r.body.page.hasMore}</div>` : ''; on('#sOut tr[data-id]', 'click', (e, el) => expenseModal(el.dataset.id)); };
    $('#sGo', root).onclick = run; $('#sQ', root).onkeydown = (e) => e.key === 'Enter' && run();
  }
  if (t === 'bulk') {
    const sample = { items: [{ amountPaise: 23400, merchant: 'Blinkit', paymentMethod: 'upi', clientRef: 'bulk-1' }, { amountPaise: 89900, merchant: 'Amazon', paymentMethod: 'card', clientRef: 'bulk-2' }, { amountPaise: -10, merchant: 'Broken row' }, { amountPaise: 23400, merchant: 'Blinkit', paymentMethod: 'upi', clientRef: 'bulk-1' }] };
    B.innerHTML = `<div class="card"><h3>Bulk create ${epTag('POST', '/v1/expenses:bulk')}</h3><div class="small mute">≤ 500 items, Idempotency-Key required, a per-item result, <span class="mono">clientRef</span> is the natural idempotency key per row (row 4 repeats row 1 → duplicate). Each created row emits its own event (batched outbox insert).</div><textarea id="bk" rows="10">${json(sample).replace(/&quot;/g, '"')}</textarea><div class="row"><button class="btn a" id="bkGo">Send</button></div><div id="bkOut"></div></div>`;
    $('#bkGo', root).onclick = () => { let b; try { b = JSON.parse(v('#bk')); } catch { return toast('Invalid JSON'); } const r = call('POST', '/v1/expenses:bulk', { body: b, headers: idem() }); $('#bkOut', root).innerHTML = r.status < 300 ? `<div class="row" style="margin:8px 0"><span class="pill ok">created ${r.body.created}</span><span class="pill info">duplicates ${r.body.duplicates}</span><span class="pill bad">invalid ${r.body.invalid}</span></div>` + table([{ l: '#', k: 'index' }, { l: 'Status', f: (x) => `<span class="pill ${x.status === 'created' ? 'ok' : x.status === 'duplicate' ? 'info' : 'bad'}">${x.status}</span>` }, { l: 'Id', f: (x) => `<span class="mono">${x.id || ''}</span>` }, { l: 'Errors', f: (x) => esc(x.errors.map((e) => e.field + ' ' + e.code).join(', ')) }], r.body.results) : jsonBox(r.body); };
  }
  if (t === 'daily') {
    const from = addDays(today(), -59); const r = call('GET', `/v1/expenses/daily-totals?from=${from}&to=${today()}`); const by = {}; for (const d of r.body.data || []) by[d.day] = (by[d.day] || 0) + d.totalPaise; const max = Math.max(1, ...Object.values(by));
    const days = Array.from({ length: 60 }, (_, i) => addDays(from, i));
    B.innerHTML = `<div class="card"><h3>Daily totals — last 60 days ${epTag('GET', '/v1/expenses/daily-totals')}</h3><div class="small mute">Not a SUM at read time: <span class="mono">expense_daily_totals (account, day, category_root)</span> is maintained by a delta upsert in the same transaction as every create/update/delete/restore (property-tested against SUM over 1,000 random ops in P07).</div>
     <div class="spark" style="height:120px;margin-top:10px">${days.map((d) => `<i title="${d}: ${rs(by[d] || 0)}" style="height:${(by[d] || 0) / max * 100}%"></i>`).join('')}</div><div class="row small mute"><span>${fmtDate(from)}</span><span class="sp"></span><span>${fmtDate(today())}</span></div>
     ${table([{ l: 'Day', k: 'day' }, { l: 'Root', f: (x) => rootDot(x.categoryRoot) + x.categoryRoot }, { l: 'Txns', n: 1, k: 'txnCount' }, { l: 'Total', n: 1, f: (x) => rs(x.totalPaise) }], (r.body.data || []).slice(-40).reverse(), { max: '300px' })}</div>`;
  }
};
function touchList() {
  if (!TOUCH.length) return '';
  const svcs = [...new Set(TOUCH.map((t) => t.svc))];
  return svcs.map((s) => `<div style="margin:6px 0"><span class="pill acc">${s}</span>${TOUCH.filter((t) => t.svc === s).map((t) => `<div class="small" style="margin:3px 0 0 8px">• <span class="mono">${esc(t.table)}</span> <b>${esc(t.key || '')}</b> <span class="mute">${esc(t.what)}</span></div>`).join('')}</div>`).join('') + `<div class="small mute">${TOUCH.length} row-level effects. Nothing else is recomputed.</div>`;
}
function expenseModal(id) {
  const r = call('GET', '/v1/expenses/' + id); if (r.status !== 200) return;
  let e = r.body, et = r.headers.ETag;
  modal('Expense ' + epTag('GET', '/v1/expenses/{id}'), `<div class="grid g2"><div>${jsonBox(e, 320)}<div class="small mute" style="margin-top:4px">ETag <span class="mono">${et}</span></div></div><div>
    <label class="f">Amount ₹<input id="eAmt" value="${e.amountPaise / 100}"></label><label class="f">Merchant<input id="eM" value="${esc(e.merchant)}"></label>
    <label class="f">Category<select id="eCat">${catOptions(e.categoryPath, false)}</select></label><label class="f">Note<input id="eNote" value="${esc(e.note || '')}"></label>
    <div class="row" style="margin-top:8px"><button class="btn a" id="eSave">PATCH (If-Match ${et})</button><button class="btn" id="eStale">PATCH with stale ETag → 412</button></div>
    <div class="row" style="margin-top:6px"><button class="btn d" id="eDel">DELETE (soft)</button><button class="btn" id="eRes">POST :restore</button></div><div id="eOut" style="margin-top:8px"></div></div></div>`, (bg) => {
    const out = (x) => { $('#eOut', bg).innerHTML = (x.status < 300 ? '<div class="note ok">' : '<div class="note bad">') + x.status + ' ' + (x.body && x.body.code || '') + '</div>' + touchList(); };
    $('#eSave', bg).onclick = () => { TOUCH.length = 0; const p = {}; const a = P2R(v('#eAmt', bg)); if (a !== e.amountPaise) p.amountPaise = a; if (v('#eM', bg) !== e.merchant) p.merchant = v('#eM', bg); if (v('#eCat', bg) !== e.categoryPath) p.categoryPath = v('#eCat', bg); if ((v('#eNote', bg) || null) !== e.note) p.note = v('#eNote', bg); const x = call('PATCH', '/v1/expenses/' + id, { body: p, headers: { 'If-Match': et } }); if (x.status === 200) { e = x.body; et = x.headers.ETag; } out(x); };
    $('#eStale', bg).onclick = () => out(call('PATCH', '/v1/expenses/' + id, { body: { note: 'stale write' }, headers: { 'If-Match': '"0"' } }));
    $('#eDel', bg).onclick = () => { TOUCH.length = 0; out(call('DELETE', '/v1/expenses/' + id)); };
    $('#eRes', bg).onclick = () => { TOUCH.length = 0; const x = call('POST', '/v1/expenses/' + id + ':restore'); if (x.status === 200) et = x.headers.ETag; out(x); };
  });
}

/* ================= INSIGHTS: calendar, summary, categories, rules, imports ================= */
SCREENS.insights = (root) => {
  const t = tabState('ins', 'calendar');
  root.innerHTML = userOnly() + tabs('ins', [['calendar', 'Calendar'], ['summary', 'Summary'], ['categories', 'Categories (ltree)'], ['rules', 'Rules'], ['imports', 'CSV import']], t) + '<div id="insB"></div>';
  bindTabs(root, 'ins', () => SCREENS.insights(root)); const B = $('#insB', root);
  if (t === 'calendar') {
    const m = state.sub.calm || monthOf(today()); const r = call('GET', '/v1/calendar?month=' + m); const c = r.body; if (r.status !== 200) return;
    const first = m + '-01', dow = (new Date(first + 'T00:00:00Z').getUTCDay() + 6) % 7, last = monthEnd(first); const days = {}; for (const d of c.days) days[d.day] = d;
    const max = Math.max(1, ...c.days.map((d) => d.spentPaise)); const cells = [];
    for (let i = 0; i < dow; i++) cells.push('<div class="d o"></div>');
    for (let d = first; d <= last; d = addDays(d, 1)) { const x = days[d]; cells.push(`<div class="d" style="${x && x.spentPaise ? `background:rgba(15,139,125,${0.08 + 0.5 * x.spentPaise / max})` : ''}${d === today() ? ';outline:2px solid var(--acc)' : ''}"><div class="row"><span class="mute">${+d.slice(8)}</span><span class="sp"></span>${x && x.spentPaise ? `<span class="a">${rsk(x.spentPaise)}</span>` : ''}</div>${x ? x.upcoming.map((u) => `<div class="pill ${u.kind === 'card_due' ? 'warn' : u.kind === 'card_emi' ? 'acc' : 'info'}" style="display:block;margin-top:2px;overflow:hidden;text-overflow:ellipsis" title="${esc(u.label)}">${rsk(u.amountPaise)} ${esc(u.label)}</div>`).join('') : ''}</div>`); }
    B.innerHTML = `<div class="card"><h3><button class="btn sm" id="cPrev">‹</button>${m}<button class="btn sm" id="cNext">›</button><span class="sp"></span>Spent <b>${rs(c.spentPaise, { whole: 1 })}</b> · ${c.txnCount} txns · upcoming <b>${rs(c.upcomingPaise, { whole: 1 })}</b> ${epTag('GET', '/v1/calendar')}</h3>
    <div class="cal">${'Mon Tue Wed Thu Fri Sat Sun'.split(' ').map((d) => `<div class="h">${d}</div>`).join('')}${cells.join('')}</div><div class="small mute" style="margin-top:6px">Daily totals (aggregate table) merged per day with upcoming liabilities, EMI instalments and card dues.</div></div>`;
    const sh = (k) => { const [y, mo] = m.split('-').map(Number); const t2 = y * 12 + mo - 1 + k; state.sub.calm = `${Math.floor(t2 / 12)}-${String(t2 % 12 + 1).padStart(2, '0')}`; SCREENS.insights(root); };
    $('#cPrev', root).onclick = () => sh(-1); $('#cNext', root).onclick = () => sh(1);
  }
  if (t === 'summary') {
    const g = state.sub.sg || 'category', from = state.sub.sf || addDays(today(), -89), to = state.sub.st || today();
    const r = call('GET', `/v1/summary?from=${from}&to=${to}&groupBy=${g}`); const s = r.body;
    B.innerHTML = `<div class="card"><div class="row end"><label class="f">From<input type="date" id="sF" value="${from}"></label><label class="f">To<input type="date" id="sT" value="${to}"></label><label class="f">Group by<select id="sG">${['category', 'merchant', 'day', 'paymentMethod'].map((x) => `<option ${x === g ? 'selected' : ''}>${x}</option>`).join('')}</select></label><button class="btn p" id="sGo">Go</button>${epTag('GET', '/v1/summary')}</div>
     <h3 style="margin-top:12px">Total ${rs(s.totalPaise || 0, { whole: 1 })}</h3>${g === 'day' ? `<div class="spark" style="height:110px">${(s.groups || []).map((x) => `<i title="${x.key} ${rs(x.totalPaise)}" style="height:${x.totalPaise / Math.max(1, ...(s.groups || []).map((y) => y.totalPaise)) * 100}%"></i>`).join('')}</div>` : ''}
     ${table([{ l: g, f: (x) => (g === 'category' ? rootDot(x.key) : '') + esc(x.label) }, { l: 'Txns', n: 1, k: 'txnCount' }, { l: 'Share', f: (x) => `<div style="width:160px">${bar(x.shareBps, 10000)}</div>` }, { l: '%', n: 1, f: (x) => pct(x.shareBps, 1) }, { l: 'Total', n: 1, f: (x) => rs(x.totalPaise) }], (s.groups || []).slice(0, 60))}</div>`;
    $('#sGo', root).onclick = () => { state.sub.sg = v('#sG'); state.sub.sf = v('#sF'); state.sub.st = v('#sT'); SCREENS.insights(root); };
  }
  if (t === 'categories') {
    const r = call('GET', '/v1/categories'); const cs = r.body.data || [];
    B.innerHTML = `<div class="split"><div class="card"><h3>Category tree ${epTag('GET', '/v1/categories')}</h3><div class="small mute">Stored as Postgres <b>ltree</b> (<span class="mono">GIST(account_id, category_path)</span>). A filter or budget on <span class="mono">food</span> matches every <span class="mono">food.*</span> row via <span class="mono">category_path &lt;@ 'food'</span> — one index range scan, no join.</div>
     <div class="tree" style="margin-top:8px;max-height:520px;overflow:auto">${cs.map((c) => `<div style="padding-left:${(c.path.split('.').length - 1) * 18}px">${rootDot(c.path)}${esc(c.name)} <span class="p">${c.path}</span> ${c.system ? '' : '<span class="pill info">custom</span>'}</div>`).join('')}</div></div>
     <div class="card"><h3>Add a custom subtree ${epTag('POST', '/v1/categories')}</h3><label class="f">Under (system category)<select id="cP">${cs.filter((c) => c.system && c.path.split('.').length < 4).map((c) => `<option>${c.path}</option>`).join('')}</select></label><label class="f">Name<input id="cN" value="Office lunch"></label><button class="btn a" id="cGo" style="margin-top:8px">Create</button><div id="cOut"></div></div></div>`;
    $('#cGo', root).onclick = () => { const x = call('POST', '/v1/categories', { body: { parentPath: v('#cP'), name: v('#cN') } }); if (x.status === 201) { dropCache('cats:' + state.persona); toast('Created ' + x.body.path); SCREENS.insights(root); } };
  }
  if (t === 'rules') {
    const r = call('GET', '/v1/rules'); const rules = r.body.data || [];
    B.innerHTML = `<div class="card"><h3>Categorisation rules ${epTag('GET', '/v1/rules')}</h3><div class="small mute">Your rules run before the resolver's alias/trigram steps (contains or exact merchant id).</div>
     ${table([{ l: 'Match', k: 'matchType' }, { l: 'Pattern', f: (x) => `<span class="mono">${esc(x.pattern)}</span>` }, { l: '→ Category', f: (x) => `<span class="mono">${x.categoryPath}</span>` }, { l: 'Priority', k: 'priority' }, { l: '', f: (x) => `<button class="btn sm d" data-del="${x.id}">Delete</button>` }], rules, { empty: 'No rules yet.' })}
     <div class="row end" style="margin-top:10px"><label class="f">Type<select id="rT"><option>contains</option><option>merchant_id</option></select></label><label class="f">Pattern<input id="rP" value="cult"></label><label class="f">Category<select id="rC">${catOptions('health.fitness', false)}</select></label><label class="f">Priority<input id="rPr" value="100" style="width:70px"></label><button class="btn a" id="rGo">Add rule</button>${epTag('POST', '/v1/rules')}</div>
     <div class="row" style="margin-top:8px"><input id="rTest" placeholder="Test a raw merchant string" value="CULT FIT*MEMBERSHIP BLR"><button class="btn" id="rTry">Resolve</button><span id="rRes" class="small"></span></div></div>`;
    on('[data-del]', 'click', (e, el) => { call('DELETE', '/v1/rules/' + el.dataset.del); SCREENS.insights(root); }, root);
    $('#rGo', root).onclick = () => { call('POST', '/v1/rules', { body: { matchType: v('#rT'), pattern: v('#rP'), categoryPath: v('#rC'), priority: +v('#rPr') } }); SCREENS.insights(root); };
    $('#rTry', root).onclick = () => { const x = call('POST', '/v1/merchants/resolve', { body: { merchant: v('#rTest') } }).body; $('#rRes', root).innerHTML = `<span class="pill">${x.method}</span> → <span class="mono">${x.category}</span> (${x.confidence})`; };
  }
  if (t === 'imports') {
    state.importIds = state.importIds || (state.live ? [] : DB.ledger.imports.filter((j) => j.accountId === curAcct()).map((j) => j.id)); const jobs = state.importIds.map((id) => call('GET', '/v1/imports/' + id, { quiet: true }).body).filter((j) => j && j.id);
    B.innerHTML = `<div class="split"><div class="card"><h3>CSV import ${epTag('POST', '/v1/imports')}</h3><div class="small mute">1) create the job → presigned PUT · 2) upload · 3) <span class="mono">:start</span> → COPY into staging, validate, merge, idempotent by clientRef (re-uploading the same file makes no duplicates).</div>
      <textarea id="csv" rows="8">occurredAt,amount,merchant,category,paymentMethod,clientRef
2026-09-21T09:10:00Z,349,Zomato,,upi,csv-a
2026-09-22T19:40:00Z,1299,Myntra,shopping.fashion,card,csv-b
2026-09-23T08:00:00Z,abc,Bad amount,,upi,csv-c
2026-09-24T12:30:00Z,220,Uber,,upi,csv-d</textarea>
      <div class="row"><input id="csvN" value="september.csv"><button class="btn a" id="csvGo">Create → upload → start</button></div><div id="csvOut"></div></div>
      <div class="card"><h3>Jobs ${epTag('GET', '/v1/imports/{id}')}</h3>${table([{ l: 'File', k: 'fileName' }, { l: 'Status', f: (j) => `<span class="pill">${j.status}</span>` }, { l: 'ok', n: 1, k: 'ok' }, { l: 'dup', n: 1, k: 'duplicates' }, { l: 'failed', n: 1, k: 'failed' }], jobs, { click: 1, rowAttr: (j) => `data-j="${j.id}"`, empty: 'No imports yet.' })}</div></div>`;
    $('#csvGo', root).onclick = () => { const lines = v('#csv').trim().split('\n'); const hd = lines[0].split(','); const rows = lines.slice(1).map((l) => Object.fromEntries(l.split(',').map((x, i) => [hd[i], x]))); const c = call('POST', '/v1/imports', { body: state.live ? { fileName: v('#csvN'), sizeBytes: v('#csv').length } : { fileName: v('#csvN'), sizeBytes: v('#csv').length, _rows: rows } }); if (c.status !== 201) return; (state.importIds = state.importIds || []).unshift(c.body.id); if (state.live && c.body.upload && c.body.upload.url) { try { const x = new XMLHttpRequest(); x.open(c.body.upload.method || 'PUT', c.body.upload.url, false); x.setRequestHeader('Content-Type', 'text/csv'); x.send(v('#csv')); } catch (e) { toast('Presigned PUT failed from the browser (object-store CORS?) — ' + e.message, 6000); } } const s = call('POST', '/v1/imports/' + c.body.id + ':start'); const g = call('GET', '/v1/imports/' + c.body.id); $('#csvOut', root).innerHTML = jsonBox(g.body, 240); setTimeout(() => SCREENS.insights(root), 1500); };
    on('[data-j]', 'click', (e, el) => modal('Import job', jsonBox(call('GET', '/v1/imports/' + el.dataset.j).body, 500)), root);
  }
};
