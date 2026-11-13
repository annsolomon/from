/* ================= PLATFORM: gateway · events · run-all ================= */
SCREENS.platform = (root) => {
  const t = tabState('pl', 'events');
  root.innerHTML = (t !== 'gateway' ? mockOnly(t === 'events' ? 'The event log' : 'Run-all') : '') + tabs('pl', [['events', 'Events (outbox → Kafka → inbox)'], ['gateway', 'Gateway edge'], ['runall', 'Run every endpoint']], t) + '<div id="plB"></div>';
  bindTabs(root, 'pl', () => SCREENS.platform(root)); const B = $('#plB', root);
  if (t === 'events') {
    B.innerHTML = `<div class="card"><h3>Event log <span class="sp"></span><span class="small mute">${EVENTS.length} events since load (seed replay excluded)</span></h3><div class="small mute">Every state change writes an outbox row in the same DB transaction; the relay publishes to Kafka (at-least-once); each consumer dedupes through its inbox (exactly-once effect). Consumers below are the real ones per the plan.</div>
     ${table([{ l: 'When', f: (e) => fmtTime(e.time) }, { l: 'Type', f: (e) => `<span class="mono">${e.type}</span>` }, { l: 'Topic', f: (e) => `<span class="mono small">${e.topic}</span>` }, { l: 'Account', f: (e) => (e.accountId ? `<span class="mono small">${e.accountId.slice(0, 8)}</span>` : 'global') }, { l: 'Consumed by', f: (e) => e.consumed.map((c) => `<span class="pill ${String(c.outcome).startsWith('APPLIED') ? 'ok' : ''}" title="${esc(c.outcome)}">${c.consumer}</span>`).join(' ') || '<span class="mute small">no consumer in this mock</span>' }], EVENTS.slice(0, 150), { max: '600px', click: 1, rowAttr: (e) => `data-ev="${e.id}"`, empty: 'No events yet — add an expense, publish a card, apply for a card…' })}</div>`;
    on('[data-ev]', 'click', (e, el) => modal('Event envelope', jsonBox(EVENTS.find((x) => x.id === el.dataset.ev), 500)), B);
  }
  if (t === 'gateway') {
    B.innerHTML = `<div class="grid g2"><div class="card"><h3>Edge filters, in order</h3>${table([{ l: '#', f: (r, i) => i + 1 }, { l: 'Filter', f: (r) => r[0] }, { l: 'Effect', f: (r) => `<span class="small">${r[1]}</span>` }], [['request-id', 'X-Request-Id kept if sane, else UUIDv7; echoed'], ['header hygiene', 'X-Account-*, X-Actor-*, roles/tenant headers stripped'], ['body size', '> 256 KB → 413 PAYLOAD_TOO_LARGE'], ['JWT pre-check', 'non-public without a valid token → 401; service tokens → 403'], ['rate limit', 'auth 10 · write 120 · read 600 · calc 60 per minute; RateLimit headers; Redis down → auth 503, others pass'], ['circuit breaker', 'per service (not SSE); open → 503, slow → 504'], ['security headers', 'nosniff, CSP default-src none, HSTS (not dev)'], ['CORS', 'allowlist per profile']])}
     <div class="row" style="margin-top:10px"><button class="btn" id="g1">No token on a user route → 401</button><button class="btn" id="g2">Unknown path → 404</button><button class="btn" id="g3">300 KB body → 413</button><button class="btn" id="g4">11 logins in a minute → 429</button><button class="btn" id="g5">Admin token on a user route</button><button class="btn" id="g6">/oauth2/token at the edge</button><button class="btn" id="g7">Gateway health</button></div><div id="gOut" style="margin-top:8px"></div></div>
     <div class="card"><h3>Route table (services)</h3>${table([{ l: 'Service', f: (r) => r[0] }, { l: 'Routes', n: 1, f: (r) => r[1] }, { l: 'Public', n: 1, f: (r) => r[2] }], Object.entries(REGISTRY.reduce((a, r) => { (a[r.svc] = a[r.svc] || [0, 0]); a[r.svc][0]++; if (r.acc === 'public') a[r.svc][1]++; return a; }, {})).map(([s, [n, p]]) => [s, n, p]))}<div class="small mute">10 deployables + the Python ai-parser (consumes Kafka only, no public routes).</div></div></div>`;
    const show = (r) => { $('#gOut', B).innerHTML = `<div class="note ${r.status < 300 ? 'ok' : 'bad'}"><b>${r.status}</b> ${r.body && r.body.code ? r.body.code : ''}</div>` + jsonBox({ headers: r.headers, body: r.body }, 220); };
    $('#g1', B).onclick = () => show(api('GET', '/v1/expenses', { token: null }));
    $('#g2', B).onclick = () => show(api('GET', '/v1/not-a-route'));
    $('#g3', B).onclick = () => show(api('POST', '/v1/calc/emi', { body: { pad: 'x'.repeat(300000) } }));
    $('#g4', B).onclick = () => { let r; for (let i = 0; i < 11; i++) r = api('POST', '/v1/auth/login', { body: { email: 'nobody@demo.in', password: 'x' }, token: null }); show(r); };
    $('#g5', B).onclick = () => show(api('GET', '/v1/expenses', { token: state.tokens.admin }));
    $('#g6', B).onclick = () => show(api('POST', '/oauth2/token', { token: null }));
    $('#g7', B).onclick = () => show(api('GET', '/actuator/health', { token: null }));
  }
  if (t === 'runall') {
    B.innerHTML = `<div class="card"><h3>Exercise all ${REGISTRY.length} routes</h3><div class="small mute">Runs every endpoint in dependency order with valid inputs, as the right persona (user / admin / anonymous). Expected refusals are part of the run: SSRF probe, a spent SSE ticket, internal-only /oauth2/token, and the two routes with no handler in the repo.</div><button class="btn a" id="ra" style="margin-top:8px">Run</button><div id="raOut" style="margin-top:10px"></div></div>`;
    $('#ra', B).onclick = () => { const t0 = performance.now(); const res = runAllEndpoints(); const ms = (performance.now() - t0).toFixed(0);
      const expected = new Set(['SSRF_BLOCKED', 'STREAM_TICKET_INVALID']); const gapPaths = ['/oauth2/token', '/v1/admin/accounts/', '/v1/budgets/category-potential'];
      const cls = (x) => x.status < 400 ? 'ok' : expected.has(x.code) ? 'info' : gapPaths.some((g) => x.url.startsWith(g)) ? 'warn' : 'bad';
      const unhit = REGISTRY.filter((r) => !r.hits);
      $('#raOut', B).innerHTML = `<div class="row"><span class="pill ok">${res.filter((x) => cls(x) === 'ok').length} succeeded</span><span class="pill info">${res.filter((x) => cls(x) === 'info').length} expected refusals</span><span class="pill warn">${res.filter((x) => cls(x) === 'warn').length} repo gaps</span><span class="pill bad">${res.filter((x) => cls(x) === 'bad').length} unexpected</span><span class="pill">${REGISTRY.length - unhit.length}/${REGISTRY.length} routes exercised</span><span class="mute small">${res.length} calls in ${ms} ms</span></div>` +
        table([{ l: 'As', k: 'who' }, { l: 'Call', f: (x) => `<span class="m ${x.m}" style="display:inline-block">${x.m}</span> <span class="mono small">${esc(x.url.slice(0, 90))}</span>` }, { l: 'Status', f: (x) => `<span class="pill ${cls(x)}">${x.status}${x.code ? ' ' + x.code : ''}</span>` }], res, { max: '560px' }); };
  }
};

/* ================= ENDPOINT INDEX ================= */
SCREENS.index = (root) => {
  const f = state.sub.ixf || ''; const svc = state.sub.ixs || '';
  const rows = REGISTRY.filter((r) => (!svc || r.svc === svc) && (!f || (r.m + ' ' + r.p + ' ' + r.d).toLowerCase().includes(f.toLowerCase())));
  const withH = REGISTRY.filter((r) => HANDLERS[r.key]).length;
  root.innerHTML = `<div class="card"><div class="row"><b>${REGISTRY.length} routes</b><span class="pill ok">${withH} implemented in this mock</span><span class="pill warn">${REGISTRY.length - withH} without a handler in the repo itself</span><span class="pill">${REGISTRY.filter((r) => r.hits).length} exercised this session</span><span class="sp"></span><input id="ixF" placeholder="filter" value="${esc(f)}"><select id="ixS"><option value="">all services</option>${[...new Set(REGISTRY.map((r) => r.svc))].map((s) => `<option ${s === svc ? 'selected' : ''}>${s}</option>`).join('')}</select></div>
   <div class="small mute" style="margin-top:4px">Extracted from every <span class="mono">@*Mapping</span> in services/*/api plus <span class="mono">docs/api/routes.md</span>. Click a row to open the screen that uses it.</div></div>
   ${table([{ l: 'Method', f: (r) => `<span class="m ${r.m}" style="display:inline-block">${r.m}</span>` }, { l: 'Path', f: (r) => `<span class="mono">${esc(r.p)}</span>` }, { l: 'Service', k: 'svc' }, { l: 'Phase', k: 'ph' }, { l: 'Access', f: (r) => `<span class="pill ${r.acc === 'public' ? 'info' : r.acc === 'admin' ? 'acc' : r.acc === 'token' ? '' : 'warn'}">${r.acc}</span>` }, { l: 'What', f: (r) => `<span class="small">${esc(r.d)}</span>` }, { l: 'Screen', f: (r) => `<a href="#${r.scr}" data-go="${r.scr}">${TITLES[r.scr] || r.scr}</a>` }, { l: 'Hits', n: 1, f: (r) => (r.hits ? `<span class="pill ok">${r.hits}</span>` : '') }, { l: '', f: (r) => (HANDLERS[r.key] ? '' : '<span class="pill warn">no handler</span>') }], rows, { max: 'none' })}`;
  $('#ixF', root).oninput = (e) => { state.sub.ixf = e.target.value; clearTimeout(window._ixT); window._ixT = setTimeout(() => { SCREENS.index(root); const i = $('#ixF'); i.focus(); i.setSelectionRange(i.value.length, i.value.length); }, 250); };
  $('#ixS', root).onchange = (e) => { state.sub.ixs = e.target.value; SCREENS.index(root); };
  on('[data-go]', 'click', (e, el) => { e.preventDefault(); go(el.dataset.go); }, root);
};

/* ================= LTREE RECALCULATION LAB ================= */
SCREENS.recalc = (root) => {
  if (state.live) { root.innerHTML = mockOnly('The recalculation lab'); return; }
  const acct = ASHA; state.persona = state.persona === 'ravi' ? 'ravi' : 'asha'; $('#persona').value = state.persona;
  const budgets = DB.planning.budgets.filter((b) => b.accountId === curAcct() && b.status === 'active');
  root.innerHTML = `<div class="card"><h3>What does one change recompute?</h3><div class="small">Pick a change. The lab performs it through the real (mock) API and lists every row the platform touched — then compares with a naive "recompute everything" design.</div>
   <div class="row" style="margin-top:8px">${[['add', 'Add ₹600 at Swiggy'], ['move', 'Re-categorise an expense food.dining → shopping.online'], ['amount', 'Change only an amount'], ['day', 'Move an expense to last month'], ['del', 'Delete an expense'], ['card', 'Publish a new card version'], ['offer', 'Create an issuer offer'], ['burst', 'Burst of 50 expenses']].map(([k, l]) => `<button class="btn" data-x="${k}">${l}</button>`).join('')}</div></div>
   <div class="split"><div class="card"><h3>Rows touched</h3><div id="rlT" class="small mute">—</div></div><div class="card"><h3>ltree containment for this change</h3><div id="rlL" class="small mute">—</div><h3 style="margin-top:10px">Naive vs actual</h3><div id="rlN" class="small mute">—</div></div></div>
   <div class="card"><h3>Pipeline</h3><div class="flow" id="rlF"></div></div>`;
  const flow = (steps) => { $('#rlF', root).innerHTML = steps.map(([h, d, cls]) => `<div class="step ${cls || 'done'}"><div class="h">${h}</div><div class="d">${d}</div></div>`).join(''); };
  const ltreeView = (paths) => budgets.map((b) => { const n = paths.filter((p) => p && isUnder(p, b.categoryPath)).length; const hit = n > 0; const net0 = n === 2 && paths.length === 2; return `<div class="row" style="margin:3px 0"><span class="pill ${net0 ? 'info' : hit ? 'ok' : ''}">${net0 ? 'both sides → net 0, no write' : hit ? 'touched' : 'skipped'}</span><span class="mono">${b.categoryPath || '(total)'}</span> ${hit ? '⊇' : '⊉'} <span class="mono">${paths.filter(Boolean).join(' / ')}</span> <span class="mute">— ${esc(b.name)}</span></div>`; }).join('');
  const naive = (extra = '') => { const n = DB.ledger.expenses.filter((e) => e.accountId === curAcct() && !e.deletedAt).length; return `<div class="kv"><b>Naive</b><span>re-SUM ${n} expenses for daily totals + every budget (${budgets.length}) + the 12-month spend profile, rescore ${DB.catalog.cards.length} cards — on every write, synchronously</span><b>Actual</b><span><b>${TOUCH.length}</b> row-level effects (deltas / upserts), one debounced recompute per account ${extra}</span></div>`; };
  const pick = (pred) => DB.ledger.expenses.find((e) => e.accountId === curAcct() && !e.deletedAt && pred(e));
  on('[data-x]', 'click', (e, el) => {
    TOUCH.length = 0; const k = el.dataset.x; let paths = [];
    if (k === 'add') { const r = call('POST', '/v1/expenses', { body: { amountPaise: 60000, merchant: 'Swiggy', paymentMethod: 'card', userCardId: DB.portfolio.cards.find((c) => c.accountId === curAcct()).id }, headers: idem() }); paths = [r.body.categoryPath]; }
    if (k === 'move') { const x = pick((e) => e.categoryPath.startsWith('food.dining')) || pick((e) => e.categoryPath.startsWith('food')); paths = [x.categoryPath, 'shopping.online']; call('PATCH', '/v1/expenses/' + x.id, { body: { categoryPath: 'shopping.online' }, headers: { 'If-Match': `"${x.version}"` } }); }
    if (k === 'amount') { const x = pick((e) => e.categoryPath.startsWith('travel')) || pick(() => true); paths = [x.categoryPath]; call('PATCH', '/v1/expenses/' + x.id, { body: { amountPaise: x.amountPaise + 12300 }, headers: { 'If-Match': `"${x.version}"` } }); }
    if (k === 'day') { const x = pick((e) => istDay(e.occurredAt) >= monthStart(today())); paths = [x.categoryPath]; call('PATCH', '/v1/expenses/' + x.id, { body: { occurredAt: new Date(Date.parse(x.occurredAt) - 32 * 86400000).toISOString() }, headers: { 'If-Match': `"${x.version}"` } }); }
    if (k === 'del') { const x = pick((e) => e.categoryPath.startsWith('shopping')) || pick(() => true); paths = [x.categoryPath]; call('DELETE', '/v1/expenses/' + x.id); }
    if (k === 'card') { const c = DB.catalog.cards[2]; acall('GET', `/v1/admin/catalog/cards/${c.id}/draft`); const d = DB.catalog.drafts[c.id]; acall('PUT', `/v1/admin/catalog/cards/${c.id}/draft`, { body: { annualFeePaise: c.annualFeePaise + 10000 }, headers: { 'If-Match': `"${d.version}"` } }); acall('POST', `/v1/admin/catalog/cards/${c.id}:publish`); }
    if (k === 'offer') { acall('POST', '/v1/admin/catalog/offers', { body: { cardProductId: DB.portfolio.cards.find((c) => c.accountId === curAcct()).cardProductId, title: '8% off cabs, max ₹80', kind: 'pct', valueBps: 800, maxDiscountPaise: 8000, categoryPath: 'travel.cab' } }); }
    if (k === 'burst') { for (let i = 0; i < 50; i++) call('POST', '/v1/expenses', { body: { amountPaise: 10000 + i * 100, merchant: i % 2 ? 'Zomato' : 'Uber', paymentMethod: 'upi' }, headers: idem(), quiet: true }); paths = ['food.delivery', 'travel.cab']; }
    const before = (DB.reco.results[curAcct()] || {}).stateHash; const s = DB.reco.recState[curAcct()] || {};
    const touched = TOUCH.slice();
    let rec;
    if (k === 'card' || k === 'offer') { rec = recoRead(curAcct()); }
    else rec = (sweep(0), DB.reco.results[curAcct()]);
    const after = (DB.reco.results[curAcct()] || {}).stateHash;
    $('#rlT', root).innerHTML = (() => { TOUCH.length = 0; TOUCH.push(...touched); return touchList(); })() || 'nothing';
    $('#rlL', root).innerHTML = paths.length ? ltreeView(paths) : (k === 'card' ? 'Catalog change: no account-level ltree work. Reward rules are matched by ltree at read (<span class="mono">:cat &lt;@ rule.category_path</span>, GIST index).' : 'Offer scope is an ltree path; best-card-now matches it at read time (<span class="mono">expense.category &lt;@ offer.category_path</span>) — nothing stored per account to recompute.');
    $('#rlN', root).innerHTML = naive(k === 'burst' ? '— 50 events coalesced into ONE recompute (debounce: sweeper claims accounts quiet for 3 s)' : '');
    const isLedger = !['card', 'offer'].includes(k);
    flow(isLedger ? [
      ['① ledger — one transaction', 'INSERT/UPDATE expenses + <b>delta upsert</b> on expense_daily_totals (before-state subtracted, after-state added: a moved day or category settles both sides) + outbox row'],
      ['② relay → Kafka <span class="mono">dexter.ledger.expense.v1</span>', 'envelope carries before + after (amount, IST day, category path, card) so consumers compute deltas without reading the ledger'],
      ['③ planning — inbox-guarded', 'load the account\'s active budgets, keep those whose path ⊇ the expense path (ltree containment), add ±amount to that (budget, period) row only; thresholds alert once per period'],
      ['④ reco — inbox-guarded', 'spend_month ±amount for (root, month); rebuild 3/6/12m windows for the touched root only; card_period_spend for best-card cap headroom; rec_state.dirty = true'],
      ['⑤ reco sweeper (debounced)', `claims dirty accounts quiet ≥3 s with FOR UPDATE SKIP LOCKED; recompute → state hash ${before ? before.slice(0, 10) : '∅'} → ${after ? after.slice(0, 10) : '∅'} ${before === after ? '(unchanged: no write, no event)' : '(changed: store + reco.recommendation.updated + SSE push)'}`],
      ['⑥ what is NOT incremental (honest)', 'the recompute itself rescoring every published card against the account\'s spend vector (≈ cards × roots, sub-millisecond). Card-benefit scoring is per account, not per category — a change in food reruns the whole account\'s ranking (but only once, only if the hash changed).', 'lit'],
    ] : k === 'card' ? [
      ['① catalog — admin publish', 'immutable version row; previous validity range closed; outbox catalog.card.published (full snapshot, compacted topic)'],
      ['② projections', 'reco catalog_cards_proj + catalog_rules_proj replaced wholesale for that card (version-guarded); portfolio + commerce projections likewise'],
      ['③ NO dirty fan-out (A-83)', 'zero rec_state rows touched — one republished card would otherwise enqueue a recompute for every account'],
      ['④ lazy freshness', `each account's next GET /v1/recommendations computes the state hash from 3 small queries; differs → recompute that one account (${rec && rec.recomputed ? 'happened just now for this account' : 'already fresh'})`],
    ] : [
      ['① catalog — offer created', 'outbox catalog.offer.changed'],
      ['② reco offers_proj upsert', 'one row; no per-account work'],
      ['③ computed at read', 'best-card-now evaluates offers for the one purchase asked about; deal savings are computed per request over ≤500 active deals. There is no stored "offer savings" to recalculate.', 'lit'],
    ]);
  }, root);
};

/* ================= ANSWERS: the three questions ================= */
SCREENS.logic = (root) => {
  root.innerHTML = `<div class="card"><h3>1 · Are the card marketplace and the offers marketplace separate?</h3>
   ${table([{ l: '', f: (r) => r[0] }, { l: 'Card marketplace', f: (r) => r[1] }, { l: 'Deals / offers marketplace', f: (r) => r[2] }], [
     ['Data', '<span class="mono">catalog.card_products</span> + versioned <span class="mono">reward_rules</span> (public, published only)', '<span class="mono">commerce.deals</span> (merchant deals, components jsonb) + <span class="mono">catalog.offers</span> (issuer card offers)'],
     ['Ranking', 'reco: 60% savings / 40% expense-fit, per account, cached by state hash', 'commerce: 0.8·(0.6·cardMatch + 0.4·savingsNorm) + 0.2·sponsor, computed per request over ≤500 active deals'],
     ['Conversion', '<span class="mono">/v1/card-applications</span> (one open per card, admin decisions)', 'signed <span class="mono">/r/{token}</span> click → affiliate postback (HMAC, idempotent)'],
     ['Endpoints', '/v1/catalog/*, /v1/recommendations*, /v1/card-applications*', '/v1/deals*, /r/*, /v1/webhooks/affiliate/*, /v1/ads/serve, /v1/catalog/offers'],
     ['Status', badge('in', 'built, separate feeds'), badge('in', 'built') + ' — but issuer offers (catalog) and merchant deals (commerce) are two disconnected offer models; deals never see catalog.offers'],
   ])}
   <div class="note small">Not a separate <i>service</i>: both card applications and deals live in the <span class="mono">commerce</span> service (and its DB schema); the card listing itself is the catalog service. Separate tables, endpoints, ranking and RLS — one deployable.</div></div>
  <div class="card"><h3>2 · Is LTREE used, and does every change do minimum recalculation?</h3>
   ${table([{ l: 'Where', f: (r) => r[0] }, { l: 'ltree', f: (r) => r[1] }, { l: 'On change, recomputes', f: (r) => r[2] }, { l: 'Verdict', f: (r) => r[3] }], [
     ['Ledger (expenses)', '<span class="mono">expenses.category_path</span>, GIST(account_id, path); filters use <span class="mono">&lt;@</span>', 'daily_totals: delta upsert of (day, root) in the same txn — ±2 rows', badge('in', 'minimal')],
     ['Planning (budgets)', '<span class="mono">budgets.category_path</span>; containment match (done in Java on the account\'s few active budgets)', 'only the (budget, period) rows whose path ⊇ the expense path, ± the delta', badge('in', 'minimal')],
     ['Reco spend profile', '<span class="mono">spend_month.category_root</span> (nlevel = 1)', 'one (root, month) delta; windows rebuilt for that root only', badge('in', 'minimal')],
     ['Reco card scoring', 'catalog_rules_proj.category_path; rule depth → share (A-81)', 'whole account rescored (all cards × roots), debounced, only if the state hash changed', badge('pa', 'per-account, not per-category')],
     ['Best card now', 'rule + offer paths matched by containment', 'card_period_spend (card, month, root) delta → cap headroom; the answer itself is computed at read', badge('in', 'minimal')],
     ['Catalog (rules, offers)', 'reward_rules.category_path GIST + EXCLUDE (no overlaps); offers.category_path GIST', 'publish = new version + projection replace; NO per-account fan-out (lazy hash)', badge('in', 'minimal')],
     ['Commerce deals', '<span class="mono">deals.category_path</span> (nlevel 1–4), feed filter <span class="mono">&lt;@</span>', 'nothing stored — savings + score computed per request', badge('pa', 'no precompute, nothing to recalc')],
     ['Offer savings in a budget', '—', 'no stored offer savings anywhere', badge('no', 'not modelled server-side')],
   ])}<div class="row" style="margin-top:8px"><a class="btn a" href="#recalc" data-go="recalc">Open the recalculation lab →</a></div></div>
  <div class="card"><h3>3a · When you plan a budget, is your typing mapped to available offers?</h3>
   <div class="note bad"><b>No — not in the backend.</b> <span class="mono">GET /v1/budgets/category-potential</span> was in the P13 plan and deferred (A-75, still in BUILD_README §7). <span class="mono">POST /v1/budgets</span> takes a category and a limit; nothing consults rates, offers or deals.</div>
   <div class="small">What exists and can be composed: <span class="mono">/v1/merchants/autocomplete</span> + <span class="mono">/resolve</span> (typing → merchant → ltree category, trigram, confidence) · <span class="mono">/v1/catalog/categories/potential</span> (best published rate) · <span class="mono">/v1/catalog/offers</span> (issuer offers by merchant/category) · <span class="mono">/v1/deals?category=</span> · <span class="mono">/v1/recommendations/best-card</span> (held card, cap headroom). The <a href="#budgets" data-go="budgets">budget planner</a> composes those five on the client and shows the offer-adjusted (net) budget, with its assumptions.</div></div>
  <div class="card"><h3>3b · Card EMI: tracking, the least-loss way out, every situation, voluntary conversion</h3><div class="small">Full breakdown on the <a href="#emi" data-go="emi">EMI center → "What the backend does / doesn't"</a>. Short version: EMI math and the tracker exist and are exact; exit paths exist for revolving debt only; foreclosure pricing, "paid" tracking, EMI-vs-revolve comparison, missed-instalment and limit-blocking are not in the backend. The EMI center adds a least-loss planner and an every-situation simulator built on exact ports of the repo's engines, labelled as proposed.</div>
   <div class="note warn small"><b>Bug found while simulating:</b> MinimumPaymentSimulator returns <span class="mono">cyclesToPayoff = 360</span> when the 360-cycle cap is hit with principal still owed (₹1,00,000 at 42.84% with a 5% minimum leaves ₹4,710.79 after 30 years) — and ExitPathCalculator repeats it as "Minimum-only takes 360 cycles".</div></div>`;
  on('[data-go]', 'click', (e, el) => { e.preventDefault(); go(el.dataset.go); }, root);
};
