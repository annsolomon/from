/* ---------- in-memory databases (one per service) seeded from the repo's own seed fixtures ---------- */
const ASHA = SEED.personas.find((p) => p.key === 'asha').accountId;
const RAVI = SEED.personas.find((p) => p.key === 'ravi').accountId;
const DB = {
  identity: { accounts: {}, sessions: [], admins: [], invites: [], elevations: [], keys: [{ kid: 'k-2026-09', alg: 'EdDSA', status: 'active', createdAt: '2026-09-01T00:00:00Z' }, { kid: 'k-2026-06', alg: 'EdDSA', status: 'published', createdAt: '2026-06-01T00:00:00Z' }], resets: {}, verifyTokens: {} },
  ledger: { expenses: [], categories: [], merchants: [], aliases: [], rules: [], imports: [], daily: new Map() },
  catalog: { issuers: [], cards: [], drafts: {}, versions: {}, offers: [], sources: [], runs: [], reviews: [] },
  portfolio: { cards: [], statements: [], txns: [], links: [], emi: [], schedule: {}, points: [], uploads: [], expProj: new Map() },
  planning: { budgets: [], spend: new Map(), alerts: new Set(), liabilities: [], lschedule: {}, scenarios: [] },
  reco: { spendMonth: new Map(), recState: {}, results: {}, history: [], tickets: {}, cardPeriod: new Map(), held: {} },
  billing: { plans: [], subs: [], payments: [], invoices: [], webhooks: new Set(), overrides: [] },
  commerce: { deals: [], clicks: [], conversions: [], counters: {}, apps: [], campaigns: [], impressions: {}, freq: {} },
  audit: { log: [], notifications: [], prefs: {}, privacy: [], activity: [] },
};
const TOUCH = []; // rows touched per event, for the recalculation lab
const touch = (svc, table, key, what) => TOUCH.push({ svc, table, key, what, at: nowIso() });

/* ---------- identity ---------- */
for (const p of SEED.personas.filter((p) => p.role === 'USER'))
  DB.identity.accounts[p.accountId] = { accountId: p.accountId, email: p.email, displayName: p.displayName, phone: null, tz: p.tz, tier: p.tier, status: 'active', emailVerified: true,
    mfa: { enrolled: p.key === 'ravi', pending: null, recoveryLeft: p.key === 'ravi' ? 10 : 0 }, password: 'Demo@12345', createdAt: p.createdAt, version: 3, failed: 0, lockedUntil: null };
const ADMIN = SEED.personas.find((p) => p.key === 'admin');
DB.identity.admins.push({ id: ADMIN.adminId, email: ADMIN.email, displayName: ADMIN.displayName, roles: ['SUPER_ADMIN'], status: 'active', breakGlassEligible: true, mfaEnrolled: true, createdAt: ADMIN.createdAt, version: 2 },
  { id: '01946839-b540-75e5-b9b0-d434591b3091', email: 'support@demo.in', displayName: 'Priya (Support)', roles: ['SUPPORT'], status: 'active', breakGlassEligible: false, mfaEnrolled: true, createdAt: '2025-02-01T04:30:00Z', version: 1 },
  { id: '01946839-b540-75e5-b9b0-d434591b3092', email: 'catalog@demo.in', displayName: 'Arjun (Catalog)', roles: ['CATALOG_EDITOR'], status: 'active', breakGlassEligible: false, mfaEnrolled: true, createdAt: '2025-02-03T04:30:00Z', version: 1 });
const PERMS = { SUPER_ADMIN: ['admin:*', 'catalog:*', 'audit:read', 'audit:verify', 'commerce:read', 'commerce:write', 'commerce:decide', 'accounts:read'], SUPPORT: ['accounts:read', 'audit:read', 'commerce:read', 'commerce:decide'], CATALOG_EDITOR: ['catalog:*', 'commerce:read', 'commerce:write'] };
function issueTokens(kind, sub, extra = {}) {
  const t = { kind, sub, access: 'eyJhbGciOiJFZERTQSIsImtpZCI6ImstMjAyNi0wOSJ9.' + btoa(JSON.stringify({ sub, kind, iat: now().getTime() })).replace(/=/g, ''), refresh: 'rt_' + uuid7().replace(/-/g, ''), issuedAt: nowIso(), stepUpAt: nowIso(), ...extra };
  return t;
}
function newSession(acct, device) { const s = { id: uuid7(), accountId: acct, device, ip: '49.36.' + Math.floor(Math.random() * 200) + '.' + Math.floor(Math.random() * 200), createdAt: nowIso(), lastSeenAt: nowIso(), revokedAt: null }; DB.identity.sessions.unshift(s); return s; }
[[ASHA, 'Pixel 9 · Chrome'], [ASHA, 'MacBook · Safari'], [RAVI, 'iPhone 15 · App']].forEach(([a, d]) => newSession(a, d));
state.tokens.asha = issueTokens('user', ASHA, { sessionId: DB.identity.sessions.find((s) => s.accountId === ASHA).id });
state.tokens.ravi = issueTokens('user', RAVI, { sessionId: DB.identity.sessions.find((s) => s.accountId === RAVI).id });
state.tokens.admin = issueTokens('admin', ADMIN.adminId, { roles: ['SUPER_ADMIN'], perms: PERMS.SUPER_ADMIN });

/* ---------- ledger: categories, merchants, resolver ---------- */
DB.ledger.categories = SEED.categories.map((c) => ({ id: c.id, accountId: null, path: c.path, name: c.name, icon: c.icon, system: true }));
DB.ledger.merchants = SEED.merchants.map((m) => ({ id: m.id, slug: m.slug, name: m.name, category: m.defaultCategory }));
for (const m of SEED.merchants) for (const a of m.aliases) DB.ledger.aliases.push({ alias: a, merchantId: m.id, weight: 1000 });
const merchantById = (id) => DB.ledger.merchants.find((m) => m.id === id);
const STOP = new Set(['pvt', 'ltd', 'private', 'limited', 'india', 'in', 'ind', 'bangalore', 'bengaluru', 'mumbai', 'delhi', 'chennai', 'hyderabad', 'pune', 'kolkata', 'gurgaon', 'noida', 'pos', 'upi', 'ecom', 'www', 'com', 'payment', 'payments']);
function normalizeMerchant(raw) {
  return String(raw || '').toLowerCase().replace(/[a-z0-9._-]+@[a-z]+/g, ' ').replace(/\*/g, ' ').replace(/[^a-z0-9 ]+/g, ' ')
    .split(/\s+/).filter((w) => w && !STOP.has(w) && !/^\d{4,}$/.test(w)).join(' ').trim();
}
function trigrams(s) { const set = new Set(); for (const w of s.split(' ').filter(Boolean)) { const p = '  ' + w + ' '; for (let i = 0; i < p.length - 2; i++) set.add(p.slice(i, i + 3)); } return set; }
function similarity(a, b) { const A = trigrams(a), B = trigrams(b); if (!A.size || !B.size) return 0; let i = 0; for (const t of A) if (B.has(t)) i++; return i / (A.size + B.size - i); }
/** P08 pipeline: normalize → user rules → exact alias → trigram (set_limit 0.45) → fallback 'general' */
function resolveMerchant(acct, raw) {
  const n = normalizeMerchant(raw);
  const rules = DB.ledger.rules.filter((r) => r.accountId === acct).sort((a, b) => b.priority - a.priority);
  for (const r of rules) if (r.matchType === 'contains' && n.includes(r.pattern.toLowerCase())) return { merchantId: null, merchant: raw, category: r.categoryPath, confidence: 1000, method: 'rule', normalized: n };
  const exact = DB.ledger.aliases.find((a) => a.alias === n);
  if (exact) { const m = merchantById(exact.merchantId); for (const r of rules) if (r.matchType === 'merchant_id' && r.pattern === m.id) return { merchantId: m.id, merchant: m.name, category: r.categoryPath, confidence: 1000, method: 'rule', normalized: n }; return { merchantId: m.id, merchant: m.name, category: m.category, confidence: 1000, method: 'alias', normalized: n }; }
  let best = null;
  for (const a of DB.ledger.aliases) { const s = similarity(n, a.alias); if (s >= 0.45 && (!best || s > best.s)) best = { s, a }; }
  if (best) { const m = merchantById(best.a.merchantId); return { merchantId: m.id, merchant: m.name, category: m.category, confidence: Math.round(best.s * 1000), method: 'trigram', normalized: n }; }
  return { merchantId: null, merchant: raw, category: 'general', confidence: 0, method: 'fallback', normalized: n };
}
function autocomplete(q) {
  const n = normalizeMerchant(q); if (n.length < 2) return [];
  const seen = new Map();
  for (const a of DB.ledger.aliases) {
    const s = a.alias.startsWith(n) ? 1 + a.weight / 10000 : similarity(n, a.alias);
    if (s >= 0.3) { const prev = seen.get(a.merchantId); if (!prev || prev < s) seen.set(a.merchantId, s); }
  }
  return [...seen.entries()].sort((x, y) => y[1] - x[1]).slice(0, 8).map(([id, s]) => { const m = merchantById(id); return { merchantId: id, name: m.name, category: m.category, score: Math.round(Math.min(1, s) * 1000) }; });
}

/* ---------- ledger write path: expense + daily totals delta in ONE transaction + outbox event ---------- */
const expState = (e) => e && !e.deletedAt ? { amountPaise: e.amountPaise, day: istDay(e.occurredAt), categoryPath: e.categoryPath, userCardId: e.userCardId, merchant: e.merchantDisplay, merchantId: e.merchantId } : null;
function dailyDelta(acct, st, sign) {
  if (!st) return;
  const k = acct + '|' + st.day + '|' + rootOf(st.categoryPath);
  const row = DB.ledger.daily.get(k) || { total: 0, count: 0 };
  row.total += sign * st.amountPaise; row.count += sign; DB.ledger.daily.set(k, row);
  touch('ledger', 'expense_daily_totals', `(${st.day}, ${rootOf(st.categoryPath)})`, (sign > 0 ? '+' : '−') + rs(st.amountPaise));
}
function writeExpense(acct, before, after, type, e) {
  dailyDelta(acct, before, -1); dailyDelta(acct, after, +1);
  touch('ledger', 'outbox', type, 'event row in the same transaction');
  return emit('dexter.ledger.expense.v1', type, acct, { expenseId: e.id, version: e.version, before, after });
}

/* ---------- planning consumer: budget_period_spend delta per matching budget (ltree containment) ---------- */
function periodFor(b, day) {
  if (b.status !== 'active' || day < b.startsOn || (b.endsOn && day > b.endsOn)) return null;
  if (b.period === 'monthly') return [monthStart(day), monthEnd(day)];
  if (b.period === 'weekly') { const dow = (new Date(day + 'T00:00:00Z').getUTCDay() + 6) % 7; const s = addDays(day, -dow); return [s, addDays(s, 6)]; }
  return [b.startsOn, b.endsOn || '9999-12-31'];
}
function budgetDelta(acct, st, sign, collect) {
  if (!st) return;
  for (const b of DB.planning.budgets.filter((x) => x.accountId === acct && x.status === 'active')) {
    if (!isUnder(st.categoryPath, b.categoryPath)) continue;
    const per = periodFor(b, st.day); if (!per) continue;
    const k = b.id + '|' + per[0];
    const x = (collect[k] = collect[k] || { d: 0, paths: new Set() }); x.d += sign * st.amountPaise; x.paths.add(st.categoryPath);
  }
}
consume('ledger.expense.', 'planning:budget-spend', (ev) => {
  const c = {}; budgetDelta(ev.accountId, ev.data.before, -1, c); budgetDelta(ev.accountId, ev.data.after, 1, c);
  let skipped0 = 0;
  for (const [k, { d, paths }] of Object.entries(c)) {
    if (!d) { skipped0++; continue; }
    const [bid, ps] = k.split('|'); const row = DB.planning.spend.get(k) || { budgetId: bid, periodStart: ps, spentPaise: 0 };
    row.spentPaise = Math.max(0, row.spentPaise + d); DB.planning.spend.set(k, row);
    const b = DB.planning.budgets.find((x) => x.id === bid);
    touch('planning', 'budget_period_spend', `(${b.name}, ${ps})`, (d > 0 ? '+' : '−') + rs(Math.abs(d)) + ` — budget path ${b.categoryPath || '(total)'} ⊇ ${[...paths].join(' & ')}`);
    for (const t of b.alertThresholds) {
      const ak = k + '|' + t;
      if (row.spentPaise * 100 >= t * b.limitPaise && !DB.planning.alerts.has(ak)) {
        DB.planning.alerts.add(ak);
        emit('dexter.planning.budget.v1', 'planning.budget.threshold-crossed', ev.accountId, { budgetId: b.id, name: b.name, threshold: t, spentPaise: row.spentPaise, limitPaise: b.limitPaise, periodStart: ps });
      }
    }
  }
  if (skipped0) touch('planning', '(no write)', skipped0 + ' slot(s)', 'budget contains both the old and the new path → net delta 0, row not written');
  return Object.keys(c).length ? 'APPLIED' : 'IGNORED (no budget path contains this category)';
});

/* ---------- reco consumers: spend_month delta, rebuild ONLY the touched root's windows, mark dirty ---------- */
function spendMonthDelta(acct, st, sign, roots) {
  if (!st) return;
  const r = rootOf(st.categoryPath), k = acct + '|' + r + '|' + monthOf(st.day);
  DB.reco.spendMonth.set(k, Math.max(0, (DB.reco.spendMonth.get(k) || 0) + sign * st.amountPaise));
  touch('reco', 'spend_month', `(${r}, ${monthOf(st.day)})`, (sign > 0 ? '+' : '−') + rs(st.amountPaise)); roots.add(r);
  if (st.userCardId) {
    const pk = acct + '|' + st.userCardId + '|' + monthOf(st.day) + '|' + r;
    DB.reco.cardPeriod.set(pk, Math.max(0, (DB.reco.cardPeriod.get(pk) || 0) + sign * st.amountPaise));
    touch('reco', 'card_period_spend', `(card …${(DB.portfolio.cards.find((c) => c.id === st.userCardId) || {}).last4}, ${r})`, 'cap headroom for best-card-now');
  }
}
const recState = (acct) => (DB.reco.recState[acct] = DB.reco.recState[acct] || { ledgerVer: 0, portfolioVer: 0, dirty: false, dirtySince: null, attempts: 0, computedAt: null });
consume('ledger.expense.', 'reco:spend-profile', (ev) => {
  const roots = new Set(); spendMonthDelta(ev.accountId, ev.data.before, -1, roots); spendMonthDelta(ev.accountId, ev.data.after, 1, roots);
  for (const r of roots) touch('reco', 'spend_profile', `(${r}, 3m|6m|12m)`, 'windows rebuilt for this root only');
  const s = recState(ev.accountId); s.ledgerVer++; if (!s.dirty) { s.dirty = true; s.dirtySince = nowIso(); }
  touch('reco', 'rec_state', ev.accountId.slice(0, 8), 'dirty = true (sweeper claims after 3 s quiet)');
});
consume('portfolio.card.', 'reco:held-cards', (ev) => { const s = recState(ev.accountId); s.portfolioVer++; s.dirty = true; s.dirtySince = s.dirtySince || nowIso(); touch('reco', 'held_cards_proj', ev.data.userCardId?.slice(0, 8), ev.type); });
function spendVector(acct, months = 3) {
  const cur = monthOf(today()); const ms = []; let y = +cur.slice(0, 4), m = +cur.slice(5, 7);
  for (let i = 0; i < months; i++) { ms.push(`${y}-${String(m).padStart(2, '0')}`); m--; if (!m) { m = 12; y--; } }
  const by = {}; const contributed = new Set();
  for (const [k, v] of DB.reco.spendMonth) { const [a, r, mo] = k.split('|'); if (a === acct && ms.includes(mo) && v > 0) { by[r] = (by[r] || 0) + v; contributed.add(mo); } }
  const n = Math.max(1, contributed.size);
  for (const r in by) by[r] = Math.round(by[r] / n);
  return by;
}

/* ---------- catalog ---------- */
const issuerSet = new Map();
for (const c of SEED.cards) issuerSet.set(c.issuerCode, { id: uuid7(), code: c.issuerCode, name: c.issuerName, type: c.issuerCode.includes('LOTUS') ? 'cobrand' : /NILGIRI|KONKAN/.test(c.issuerCode) ? 'nbfc' : 'bank' });
DB.catalog.issuers = [...issuerSet.values()];
DB.catalog.cards = SEED.cards.map((c) => ({ ...c, status: 'published', currentVersion: 1, updatedAt: '2026-09-01T00:00:00Z' }));
for (const c of DB.catalog.cards) DB.catalog.versions[c.id] = [{ version: 1, effectiveFrom: '2026-01-01T00:00:00Z', effectiveTo: null, snapshot: JSON.parse(JSON.stringify(c)) }];
const cardById = (id) => DB.catalog.cards.find((c) => c.id === id);
const cardBySlug = (s) => DB.catalog.cards.find((c) => c.slug === s);
const mId = (slug) => (DB.ledger.merchants.find((m) => m.slug === slug) || {}).id || null;
(function seedOffers() {
  const cs = DB.catalog.cards;
  const mk = (i, title, kind, val, max, minS, cat, mslug, card) => ({ id: uuid7(), issuerCode: (card || cs[i]).issuerCode, cardProductId: card === null ? null : (card || cs[i]).id, merchantId: mslug ? mId(mslug) : null, categoryPath: cat, title, kind, valueBps: kind === 'pct' ? val : null, valuePaise: kind !== 'pct' ? val : null, maxDiscountPaise: max, minSpendPaise: minS, valid: ['2026-09-01T00:00:00Z', '2026-12-31T23:59:59Z'], stackable: i % 2 === 0, status: 'active', version: 1, termsUrl: 'https://example.invalid/terms' });
  DB.catalog.offers = [
    mk(0, '10% off food delivery, up to ₹120', 'pct', 1000, 12000, 39900, 'food.delivery', null),
    mk(1, 'Flat ₹750 off flights over ₹7,500', 'flat', 75000, 75000, 750000, 'travel.air', null),
    mk(2, '5% cashback on groceries, up to ₹250', 'cashback', 25000, 25000, 150000, 'food.groceries', null),
    mk(0, '15% off at Zomato, up to ₹150', 'pct', 1500, 15000, 29900, 'food.delivery', 'zomato'),
    mk(3, 'Flat ₹100 off movie tickets', 'flat', 10000, 10000, 50000, 'entertainment', null),
    mk(4, '7.5% off electronics, up to ₹2,500', 'pct', 750, 250000, 1000000, 'shopping.electronics', null),
  ].filter((o) => o.cardProductId);
})();

/* ---------- portfolio ---------- */
function cycleOf(day, statementDay, dueOffset) {
  const [y, m, d] = day.split('-').map(Number);
  const sd = (yy, mm) => { const last = new Date(Date.UTC(yy, mm, 0)).getUTCDate(); return statementDay === 31 ? last : Math.min(statementDay, last); };
  let ey = y, em = m; if (d > sd(y, m)) { em++; if (em > 12) { em = 1; ey++; } }
  const end = `${ey}-${String(em).padStart(2, '0')}-${String(sd(ey, em)).padStart(2, '0')}`;
  let py = ey, pm = em - 1; if (!pm) { pm = 12; py--; }
  const start = addDays(`${py}-${String(pm).padStart(2, '0')}-${String(sd(py, pm)).padStart(2, '0')}`, 1);
  return { periodStart: start, periodEnd: end, dueDate: addDays(end, dueOffset) };
}
DB.portfolio.cards = SEED.userCards.map((c) => ({ id: c.id, accountId: c.accountId, cardProductId: c.cardProductId, nickname: c.nickname, last4: c.last4, network: c.network, statementDay: c.statementDay, dueOffsetDays: c.dueOffsetDays, creditLimitPaise: c.creditLimitPaise, openedOn: c.openedOn, status: c.status, createdAt: c.openedOn + 'T00:00:00Z', updatedAt: '2026-09-01T00:00:00Z', version: c.version }));
for (const c of DB.portfolio.cards) DB.reco.held[c.accountId] = [...(DB.reco.held[c.accountId] || []), c.id];
function earnFor(card, catPath, merchantId, amount) {
  const prod = cardById(card.cardProductId); if (!prod) return { type: 'cashback', rateBps: 0, valuePaise: 0, points: 0 };
  const rules = prod.rules.filter((r) => (r.minTxnPaise || 0) <= amount && !(r.excludedPaths || []).some((x) => isUnder(catPath, x)) && (r.categoryPath == null || isUnder(catPath, r.categoryPath)) && (!r.merchantSlug || mId(r.merchantSlug) === merchantId));
  rules.sort((a, b) => (b.merchantSlug ? 1 : 0) - (a.merchantSlug ? 1 : 0) || (b.categoryPath || '').split('.').length - (a.categoryPath || '').split('.').length || b.priority - a.priority);
  const r = rules[0]; if (!r) return { type: 'cashback', rateBps: 0, valuePaise: 0, points: 0, ruleId: null };
  const vm = Math.floor(amount * r.rateBps * 1000 / 10000);
  const pts = r.rewardType !== 'cashback' && r.pointValueMilliPaise > 0 ? Math.floor(vm / r.pointValueMilliPaise) : 0;
  return { type: r.rewardType, rateBps: r.rateBps, valuePaise: Math.floor((pts ? pts * r.pointValueMilliPaise : vm) / 1000), points: pts, ruleId: r.id, rule: r };
}
function recordTxn(card, t) {
  const cyc = cycleOf(istDay(t.postedAt), card.statementDay, card.dueOffsetDays);
  let st = DB.portfolio.statements.find((s) => s.userCardId === card.id && s.periodStart === cyc.periodStart);
  if (!st) { st = { id: uuid7(), accountId: card.accountId, userCardId: card.id, ...cyc, debitsPaise: 0, creditsPaise: 0, txnCount: 0, totalDuePaise: 0, minDuePaise: 0, status: 'open', version: 0 }; DB.portfolio.statements.push(st); }
  t.statementId = st.id; st.txnCount++;
  if (t.direction === 'debit') st.debitsPaise += t.amountPaise; else st.creditsPaise += t.amountPaise;
  st.totalDuePaise = Math.max(0, st.debitsPaise - st.creditsPaise);
  if (cyc.periodEnd < today()) { st.status = 'generated'; st.minDuePaise = Math.min(st.totalDuePaise, Math.max(10000, Math.round(st.totalDuePaise * 0.05))); }
  if (t.direction === 'debit') {
    const e = earnFor(card, t.categoryPath, t.merchantId, t.amountPaise); t.reward = { type: e.type, rateBps: e.rateBps, valuePaise: e.valuePaise, points: e.points };
    if (e.points) DB.portfolio.points.push({ id: uuid7(), accountId: card.accountId, userCardId: card.id, kind: 'earn', points: e.points, valuePaise: e.valuePaise, expiresOn: addDays(istDay(t.postedAt), 1095), sourceRef: 'txn:' + t.id, cardTxnId: t.id, note: null, createdAt: t.postedAt });
  }
  DB.portfolio.txns.push(t);
  return t;
}
/* ---------- planning seed ---------- */
const BUD = (name, cat, lim, period = 'monthly') => ({ id: uuid7(), accountId: ASHA, name, period, categoryPath: cat, limitPaise: lim, rollover: false, alertThresholds: [50, 80, 100], startsOn: '2026-06-01', endsOn: null, status: 'active', createdAt: '2026-06-01T03:00:00Z', updatedAt: '2026-06-01T03:00:00Z', version: 0 });
DB.planning.budgets.push(BUD('Everything', null, 9000000), BUD('Food', 'food', 1800000), BUD('Food delivery', 'food.delivery', 700000), BUD('Shopping', 'shopping', 1500000), BUD('Travel', 'travel', 1200000), BUD('Weekly cabs', 'travel.cab', 150000, 'weekly'));

/* ---------- replay the seed through the real write path (so every projection is consistent) ---------- */
(function replay() {
  const quiet = window.onEvent; window.onEvent = null;
  for (const e of SEED.expenses) {
    const row = { id: e.id, accountId: e.accountId, occurredAt: e.occurredAt, amountPaise: e.amountPaise, currency: 'INR', merchantDisplay: e.merchantDisplay, merchantId: e.merchantId, categoryPath: e.categoryPath,
      categorySource: e.categorySource, resolutionConfidence: e.resolutionConfidence, paymentMethod: e.paymentMethod, userCardId: e.userCardId, note: e.note, tags: e.tags || [], clientRef: null, source: e.source, createdAt: e.occurredAt, updatedAt: e.occurredAt, version: e.version || 0, deletedAt: null };
    DB.ledger.expenses.push(row);
    writeExpense(row.accountId, null, expState(row), 'ledger.expense.created', row);
    if (row.userCardId) {
      const card = DB.portfolio.cards.find((c) => c.id === row.userCardId);
      if (card) {
        const t = recordTxn(card, { id: uuid7(), accountId: card.accountId, userCardId: card.id, postedAt: new Date(Date.parse(row.occurredAt) + 86400000).toISOString(), amountPaise: row.amountPaise, direction: 'debit', kind: 'purchase',
          description: (row.merchantDisplay || '').toUpperCase().replace(/ /g, '*').slice(0, 14) + ' BANGALORE IN', merchantId: row.merchantId, categoryPath: row.categoryPath, expenseId: null, clientRef: null, source: 'manual', createdAt: row.occurredAt });
        // older ones were already confirmed; the last fortnight's are left for the auto-matcher to suggest
        if (row.occurredAt < '2026-09-14') { t.expenseId = row.id; DB.portfolio.links.push({ id: uuid7(), accountId: card.accountId, expenseId: row.id, transactionId: t.id, status: 'confirmed', score: 0.97, method: 'auto', createdAt: row.occurredAt, decidedAt: row.occurredAt }); }
        else DB.portfolio.links.push({ id: uuid7(), accountId: card.accountId, expenseId: row.id, transactionId: t.id, status: 'suggested', score: +(0.86 + Math.random() * 0.13).toFixed(3), method: 'auto', createdAt: t.postedAt, decidedAt: null });
      }
    }
  }
  TOUCH.length = 0; EVENTS.length = 0; window.onEvent = quiet;
  for (const s of Object.values(DB.reco.recState)) { s.dirty = false; s.dirtySince = null; }
})();
// a payment credit per generated statement
for (const st of DB.portfolio.statements.filter((s) => s.status === 'generated')) {
  const card = DB.portfolio.cards.find((c) => c.id === st.userCardId);
  if (st.dueDate < today()) { recordTxn(card, { id: uuid7(), accountId: card.accountId, userCardId: card.id, postedAt: st.dueDate + 'T06:00:00Z', amountPaise: st.totalDuePaise, direction: 'credit', kind: 'payment', description: 'PAYMENT RECEIVED - THANK YOU', merchantId: null, categoryPath: null, expenseId: null, clientRef: null, source: 'manual', createdAt: st.dueDate + 'T06:00:00Z', reward: null }); }
}
for (const st of DB.portfolio.statements) { if (st.status === 'generated') st.status = st.dueDate < today() ? 'paid' : 'generated'; }
{ const c0 = DB.portfolio.cards.find((c) => c.accountId === ASHA).id;
  DB.portfolio.points.push({ id: uuid7(), accountId: ASHA, userCardId: c0, kind: 'adjust', points: 2400, valuePaise: 60000, expiresOn: '2026-11-20', sourceRef: 'adjust:welcome-bonus', cardTxnId: null, note: 'Welcome bonus (mock)', createdAt: '2025-11-20T06:00:00Z' },
    { id: uuid7(), accountId: ASHA, userCardId: c0, kind: 'adjust', points: 3100, valuePaise: 77500, expiresOn: '2028-03-01', sourceRef: 'adjust:milestone', cardTxnId: null, note: 'Milestone points (mock)', createdAt: '2026-03-01T06:00:00Z' }); }
DB.portfolio.points.push({ id: uuid7(), accountId: ASHA, userCardId: DB.portfolio.cards.find((c) => c.accountId === ASHA).id, kind: 'redeem', points: -500, valuePaise: -12500, expiresOn: null, sourceRef: 'redeem:voucher', cardTxnId: null, note: 'Amazon-style voucher (mock)', createdAt: '2026-08-20T06:00:00Z' });

/* ---------- EMI + liabilities seed ---------- */
function makeEmi(acct, cardId, desc, principal, rate, n, fee, firstDue, txnId = null) {
  const s = DexCalc.emiSchedule(principal, rate, n, 1800, fee, firstDue);
  const p = { id: uuid7(), accountId: acct, userCardId: cardId, cardTxnId: txnId, description: desc, principalPaise: principal, annualRateBps: rate, tenureMonths: n, processingFeePaise: fee, gstBps: 1800, firstDueDate: firstDue,
    emiPaise: s.emiPaise, totalInterestPaise: s.totalInterestPaise, totalGstPaise: s.totalGstPaise, status: 'active', createdAt: nowIso(), updatedAt: nowIso(), version: 0 };
  DB.portfolio.emi.push(p); DB.portfolio.schedule[p.id] = s.installments.map((i) => ({ ...i, status: 'due' }));
  return p;
}
{ const c = DB.portfolio.cards.filter((c) => c.accountId === ASHA);
  makeEmi(ASHA, c[1].id, 'Phone (converted purchase)', 4500000, 1599, 9, 19900, '2026-07-26');
  makeEmi(ASHA, c[0].id, 'Laptop — no-cost EMI', 7200000, 1500, 6, 19900, '2026-09-25'); }
function makeLiability(acct, kind, lender, principal, apr, n, start) {
  const s = DexCalc.emiSchedule(principal, apr, n, 0, 0, start);
  const l = { id: uuid7(), accountId: acct, kind, lender, principalPaise: principal, aprBps: apr, tenureMonths: n, startDate: start, lastDueDate: DexCalc.addMonths(start, n - 1), emiPaise: s.emiPaise, totalInterestPaise: s.totalInterestPaise, totalPayablePaise: principal + s.totalInterestPaise, status: 'active', createdAt: nowIso(), updatedAt: nowIso(), version: 0 };
  DB.planning.liabilities.push(l); DB.planning.lschedule[l.id] = s.installments; return l;
}
makeLiability(ASHA, 'loan', 'Kaveri Car Loan (MOCK)', 60000000, 925, 60, '2025-04-05');
makeLiability(ASHA, 'bnpl', 'PayLater (MOCK)', 1200000, 0, 3, '2026-09-15');

/* ---------- billing ---------- */
DB.billing.plans = [
  { code: 'FREE', name: 'Free', pricePaise: 0, interval: 'month', entitlements: { adFree: false, maxCards: 3, walletOptimizer: false, statementUploadsPerMonth: 1, bestCardNow: true, sse: false }, active: true },
  { code: 'PREMIUM', name: 'Premium', pricePaise: 14900, interval: 'month', entitlements: { adFree: true, maxCards: 10, walletOptimizer: true, statementUploadsPerMonth: 10, bestCardNow: true, sse: true }, active: true },
  { code: 'VIP', name: 'VIP', pricePaise: 149900, interval: 'year', entitlements: { adFree: true, maxCards: 50, walletOptimizer: true, statementUploadsPerMonth: 100, bestCardNow: true, sse: true, prioritySupport: true }, active: true }];
function gstSplit(price, state) { const intra = state === 'KA'; const tax = Math.round(price * 0.18); return intra ? { regime: 'CGST+SGST', lines: [['CGST', 900, Math.round(tax / 2)], ['SGST', 900, tax - Math.round(tax / 2)]], tax } : { regime: 'IGST', lines: [['IGST', 1800, tax]], tax }; }
function makeInvoice(acct, sub, plan, state, at) {
  const g = gstSplit(plan.pricePaise, state);
  const inv = { id: uuid7(), accountId: acct, number: 'DXT/26-27/' + String(DB.billing.invoices.length + 1041).padStart(6, '0'), issuedAt: at, periodStart: sub.currentPeriodStart, periodEnd: sub.currentPeriodEnd, supplierState: 'KA', placeOfSupply: state, taxRegime: g.regime,
    subtotalPaise: plan.pricePaise, taxPaise: g.tax, totalPaise: plan.pricePaise + g.tax, currency: 'INR', status: 'paid', subscriptionId: sub.id, paymentId: uuid7(),
    lines: [{ lineNo: 1, kind: 'item', description: `Dexter ${plan.name} (${plan.interval})`, rateBps: null, amountPaise: plan.pricePaise }, ...g.lines.map(([k, r, a], i) => ({ lineNo: i + 2, kind: 'tax', description: k, rateBps: r, amountPaise: a }))] };
  DB.billing.invoices.unshift(inv); return inv;
}
{ const s = { id: uuid7(), accountId: ASHA, planCode: 'PREMIUM', status: 'active', currentPeriodStart: '2026-09-12T00:00:00Z', currentPeriodEnd: '2026-10-12T00:00:00Z', cancelAtPeriodEnd: false, canceledAt: null, endedAt: null, provider: 'mock', createdAt: '2026-06-12T00:00:00Z', updatedAt: '2026-09-12T00:00:00Z', version: 4 };
  DB.billing.subs.push(s);
  for (const m of ['2026-07-12', '2026-08-12', '2026-09-12']) makeInvoice(ASHA, { ...s, currentPeriodStart: m + 'T00:00:00Z', currentPeriodEnd: DexCalc.addMonths(m, 1) + 'T00:00:00Z' }, DB.billing.plans[1], 'TN', m + 'T00:01:00Z'); }

/* ---------- commerce ---------- */
(function seedDeals() {
  const cs = DB.catalog.cards;
  const D_ = (title, mslug, cat, comps, elig, ref, sponsor, pri, desc) => ({ id: uuid7(), merchantId: mId(mslug), merchantName: (DB.ledger.merchants.find((m) => m.slug === mslug) || { name: mslug }).name, title, description: desc, categoryPath: cat, components: comps,
    eligibleCards: elig.map((i) => ({ cardProductId: cs[i].id, name: cs[i].name })), referenceSpendPaise: ref, sponsorWeight: sponsor, priority: pri, status: 'active', validFrom: '2026-09-01T00:00:00Z', validTo: '2026-12-31T23:59:59Z', affiliateUrl: 'https://merchant.example/' + mslug, version: 1, createdAt: '2026-09-01T00:00:00Z' });
  const pctC = (bps, cap, min = null) => ({ kind: 'percent', valueBps: bps, capPaise: cap, valuePaise: null, minSpendPaise: min });
  const flatC = (v, min = null) => ({ kind: 'flat', valueBps: null, capPaise: null, valuePaise: v, minSpendPaise: min });
  DB.commerce.deals = [
    D_('Big electronics week: 10% + 5% card cashback', 'croma', 'shopping.electronics', [pctC(1000, 500000), pctC(500, 200000)], [0, 1], 7500000, 0.6, 5, 'Golden case from ADR-0023: ₹75,000 → ₹5,000 + ₹2,000 = ₹7,000'),
    D_('₹150 off your next 3 food orders', 'swiggy', 'food.delivery', [flatC(15000, 49900)], [], 60000, 0.2, 3, 'Open to any card'),
    D_('Flights: 12% off up to ₹2,000', 'makemytrip-flights', 'travel.air', [pctC(1200, 200000, 500000)], [2], 1500000, 0, 4, 'Card-exclusive'),
    D_('Groceries: 7% off, up to ₹350', 'bigbasket', 'food.groceries', [pctC(700, 35000)], [], 400000, 0, 2, ''),
    D_('Fuel: ₹75 back on ₹1,500+', 'indian-oil', 'fuel.petrol', [flatC(7500, 150000)], [3], 300000, 0, 1, ''),
    D_('Fashion fest: 20% + flat ₹300', 'myntra', 'shopping.fashion', [pctC(2000, 100000), flatC(30000, 300000)], [4], 450000, 0.8, 6, 'Sponsored'),
    D_('Movie Tuesdays: 25% off, max ₹200', 'bookmyshow', 'entertainment', [pctC(2500, 20000)], [], 80000, 0, 1, ''),
    D_('Hotels: 15% off, max ₹4,000', 'oyo', 'travel.hotel', [pctC(1500, 400000, 600000)], [1, 2], 1200000, 0.3, 3, ''),
    D_('Cab rides: 10% back, max ₹60', 'uber', 'travel.cab', [pctC(1000, 6000)], [0], 40000, 0, 1, ''),
  ];
  const now_ = '2026-09-01T00:00:00Z';
  DB.commerce.campaigns = [
    { id: uuid7(), name: 'Saffron Millennia launch', advertiser: 'Saffron Bank (MOCK)', status: 'active', validFrom: now_, validTo: '2026-12-31T00:00:00Z', weight: 3, frequencyCap: 3, frequencyWindowSeconds: 86400, placements: ['home', 'deals'], version: 1, createdAt: now_,
      creatives: [{ id: uuid7(), headline: '5% back on food delivery', body: 'MOCK_Saffron Millennia — apply in 2 minutes', imageUrl: '', clickUrl: 'https://ads.example/saffron', weight: 2, status: 'active', version: 1 }, { id: uuid7(), headline: 'Zero joining fee this month', body: 'Limited period', imageUrl: '', clickUrl: 'https://ads.example/saffron2', weight: 1, status: 'active', version: 1 }] },
    { id: uuid7(), name: 'Travel season', advertiser: 'Indus Valley Bank (MOCK)', status: 'active', validFrom: now_, validTo: '2026-12-31T00:00:00Z', weight: 1, frequencyCap: 2, frequencyWindowSeconds: 86400, placements: ['deals'], version: 1, createdAt: now_,
      creatives: [{ id: uuid7(), headline: 'Lounge access on every trip', body: 'MOCK_Indus travel cards', imageUrl: '', clickUrl: 'https://ads.example/indus', weight: 1, status: 'active', version: 1 }] }];
  DB.commerce.apps.push({ id: uuid7(), accountId: ASHA, cardProductId: cs[6].id, status: 'in_review', history: [{ status: 'submitted', at: '2026-09-20T05:00:00Z', by: 'user' }, { status: 'in_review', at: '2026-09-21T06:00:00Z', by: 'support@demo.in' }], reason: null, version: 2, createdAt: '2026-09-20T05:00:00Z', updatedAt: '2026-09-21T06:00:00Z', decidedAt: null });
})();

/* ---------- audit ---------- */
async function sha(s) { const b = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s)); return [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, '0')).join(''); }
function hashSync(s) { let h1 = 0x811c9dc5, h2 = 0x1b873593; for (let i = 0; i < s.length; i++) { h1 = Math.imul(h1 ^ s.charCodeAt(i), 16777619); h2 = Math.imul(h2 ^ s.charCodeAt(i), 2246822507); } return ((h1 >>> 0).toString(16).padStart(8, '0') + (h2 >>> 0).toString(16).padStart(8, '0')).repeat(4); }
function audit(actorType, actorId, action, targetType, targetId, details = {}) {
  const prev = DB.audit.log[0]; const seq = prev ? prev.seq + 1 : 1;
  const row = { seq, occurredAt: nowIso(), id: uuid7(), actorType, actorId, action, targetType, targetId, requestId: uuid7(), details, prevHash: prev ? prev.hash : '0'.repeat(64) };
  row.hash = hashSync(JSON.stringify([row.seq, row.occurredAt, row.actorType, row.actorId, row.action, row.targetType, row.targetId, JSON.stringify(row.details), row.prevHash]));
  DB.audit.log.unshift(row); return row;
}
[['user', ASHA, 'auth.login', 'session', 'web'], ['admin', ADMIN.adminId, 'admin.login', 'admin', ADMIN.adminId], ['admin', ADMIN.adminId, 'catalog.card.published', 'card_product', DB.catalog.cards[0].id], ['user', ASHA, 'billing.checkout', 'subscription', DB.billing.subs[0].id], ['user', RAVI, 'auth.mfa.enrolled', 'account', RAVI]]
  .forEach((a) => audit(...a));
function notify(acct, category, template, title, body, payload = {}) {
  const prefs = DB.audit.prefs[acct] || { email: true, push: true, muted: [], version: 0 };
  if (category !== 'security' && prefs.muted.includes(category)) return null;
  const n = { id: uuid7(), accountId: acct, category, template, title, body, payload, createdAt: nowIso(), readAt: null, channels: ['inapp', ...(prefs.email || category === 'security' ? ['email'] : []), ...(prefs.push ? ['push'] : [])] };
  DB.audit.notifications.unshift(n); return n;
}
consume('planning.budget.threshold-crossed', 'audit:notifications', (ev) => { const d = ev.data; notify(ev.accountId, 'budget', 'budget.threshold', `${d.name}: ${d.threshold}% used`, `${rs(d.spentPaise)} of ${rs(d.limitPaise)} this period.`, d); });
consume('commerce.application.status-changed', 'audit:notifications', (ev) => notify(ev.accountId, 'commerce', 'application.status', `Application ${ev.data.status}`, `${ev.data.cardName}: ${ev.data.status}${ev.data.reason ? ' — ' + ev.data.reason : ''}`, ev.data));
consume('billing.subscription.changed', 'audit:notifications', (ev) => notify(ev.accountId, 'billing', 'subscription.changed', `Subscription ${ev.data.status}`, `${ev.data.planCode} · ${ev.data.status}`, ev.data));
consume('identity.security.', 'audit:notifications', (ev) => notify(ev.accountId, 'security', ev.type, ev.data.title, ev.data.body, ev.data));
DB.audit.prefs[ASHA] = { email: true, push: true, muted: [], version: 1 };
notify(ASHA, 'security', 'identity.security.new-login', 'New sign-in', 'MacBook · Safari from Bengaluru');
notify(ASHA, 'budget', 'budget.threshold', 'Food delivery: 80% used', 'Mock seed notification');
notify(ASHA, 'billing', 'subscription.changed', 'Payment received', 'PREMIUM renewed for Sep–Oct');
DB.audit.notifications[2].readAt = '2026-09-12T08:00:00Z';
for (let h = 0; h < 48; h++) for (const svc of ['ledger', 'reco', 'catalog', 'portfolio', 'planning', 'identity', 'commerce', 'billing', 'audit']) {
  const base = { ledger: 900, reco: 600, catalog: 700, portfolio: 400, planning: 300, identity: 250, commerce: 350, billing: 60, audit: 120 }[svc];
  const req = Math.round(base * (0.6 + 0.6 * Math.sin((h + 6) / 24 * Math.PI * 2) ** 2) * (0.85 + Math.random() * 0.3));
  DB.audit.activity.push({ hour: new Date(Date.parse(NOW_ISO) - (47 - h) * 3600000).toISOString().slice(0, 13) + ':00:00Z', service: svc, requests: req, clientErrors: Math.round(req * 0.012), serverErrors: Math.random() < 0.08 ? Math.round(Math.random() * 3) : 0, avgMs: Math.round(8 + Math.random() * (svc === 'reco' ? 30 : 14)), maxMs: Math.round(60 + Math.random() * 300) });
}

/* ---------- ingestion ---------- */
(function seedIngestion() {
  const cs = DB.catalog.cards;
  DB.catalog.sources = [
    { id: uuid7(), issuerCode: cs[0].issuerCode, cardProductId: cs[0].id, url: 'https://saffron-bank.example/cards/millennia', host: 'saffron-bank.example', kind: 'html', scheduleCron: '0 3 * * *', enabled: true, nextRunAt: '2026-09-29T03:00:00Z', lastRunAt: '2026-09-28T03:00:00Z', createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z', version: 0 },
    { id: uuid7(), issuerCode: cs[2].issuerCode, cardProductId: cs[2].id, url: 'https://lotus.example/mitc.pdf', host: 'lotus.example', kind: 'pdf', scheduleCron: '0 4 * * 1', enabled: true, nextRunAt: '2026-10-05T04:00:00Z', lastRunAt: '2026-09-28T04:00:00Z', createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z', version: 0 },
    { id: uuid7(), issuerCode: cs[4].issuerCode, cardProductId: null, url: 'https://kaveri.example/sitemap.xml', host: 'kaveri.example', kind: 'sitemap', scheduleCron: '0 2 * * 0', enabled: false, nextRunAt: null, lastRunAt: '2026-09-21T02:00:00Z', createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-09-01T00:00:00Z', version: 1 }];
  DB.catalog.runs = [{ id: uuid7(), trigger: 'schedule', status: 'succeeded', sourceIds: [DB.catalog.sources[0].id], attempts: 1, errorCode: null, checkpoint: { done: 1, of: 1 }, createdAt: '2026-09-28T03:00:00Z', startedAt: '2026-09-28T03:00:01Z', finishedAt: '2026-09-28T03:00:09Z' }];
  DB.catalog.reviews = [
    { id: uuid7(), status: 'pending', cardProductId: cs[0].id, cardSlug: cs[0].slug, cardName: cs[0].name, baseVersion: 1, confidence: 0.94, changes: [{ path: 'annualFeePaise', from: cs[0].annualFeePaise, to: cs[0].annualFeePaise + 50000, confidence: 0.97 }, { path: 'rules[food.delivery].rateBps', from: 500, to: 600, confidence: 0.88 }], sourceId: DB.catalog.sources[0].id, snapshotId: uuid7(), url: DB.catalog.sources[0].url, fetchedAt: '2026-09-28T03:00:05Z', createdAt: '2026-09-28T03:00:09Z', reviewerId: null, decidedAt: null, reason: null, promotion: null },
    { id: uuid7(), status: 'pending', cardProductId: cs[2].id, cardSlug: cs[2].slug, cardName: cs[2].name, baseVersion: 1, confidence: 0.81, changes: [{ path: 'aprBps', from: cs[2].aprBps, to: 4560, confidence: 0.81 }], sourceId: DB.catalog.sources[1].id, snapshotId: uuid7(), url: DB.catalog.sources[1].url, fetchedAt: '2026-09-28T04:00:04Z', createdAt: '2026-09-28T04:00:06Z', reviewerId: null, decidedAt: null, reason: null, promotion: null }];
})();
