/* "Run every endpoint": exercises all routes in a dependency-correct order with valid inputs, as each persona. */
function runAllEndpoints() {
  const res = []; const prev = state.persona; for (const k in rateLeft) delete rateLeft[k]; window.SYNC_SAGA = true;
  const call = (who, m, url, opt = {}) => { state.persona = who; const r = api(m, url, { ...opt, noRate: true }); res.push({ who, m, url, status: r.status, code: r.body && r.body.code }); return r; };
  const K = () => ({ 'Idempotency-Key': uuid7() });
  const A = 'asha', AD = 'admin';
  // identity
  const reg = call('anon', 'POST', '/v1/auth/register', { body: { email: 'new.user' + Math.floor(Math.random() * 1e6) + '@demo.in', password: 'Very$ecret123', displayName: 'New User', tz: 'Asia/Kolkata' }, headers: K() });
  call('anon', 'POST', '/v1/auth/verify-email', { body: { token: reg.body._devVerifyToken } });
  const lg = call('anon', 'POST', '/v1/auth/login', { body: { email: 'ravi@demo.in', password: 'Demo@12345', deviceLabel: 'Run-all' } });
  call('anon', 'POST', '/v1/auth/mfa/verify', { body: { mfaToken: lg.body.mfaToken, code: '123456' } });
  call(A, 'POST', '/v1/auth/step-up', { body: { password: 'Demo@12345' } });
  call('anon', 'POST', '/v1/auth/token/refresh', { body: { refreshToken: state.tokens.ravi.refresh } });
  const fg = call('anon', 'POST', '/v1/auth/password/forgot', { body: { email: 'ravi@demo.in' } });
  call('anon', 'POST', '/v1/auth/password/reset', { body: { token: fg.body._devResetToken, newPassword: 'Demo@12345' } });
  const me = call(A, 'GET', '/v1/me');
  call(A, 'PATCH', '/v1/me', { body: { displayName: 'Asha Iyer' }, headers: { 'If-Match': me.headers.ETag } });
  const ss = call(A, 'GET', '/v1/me/sessions');
  const other = ss.body.data.find((s) => !s.current); if (other) call(A, 'DELETE', '/v1/me/sessions/' + other.id);
  call(A, 'GET', '/v1/me/mfa');
  call(A, 'POST', '/v1/me/mfa/totp/enroll'); call(A, 'POST', '/v1/me/mfa/totp/confirm', { body: { code: '654321' } });
  call(A, 'POST', '/v1/me/mfa/recovery-codes'); call(A, 'DELETE', '/v1/me/mfa/totp');
  call(A, 'POST', '/v1/auth/password/change', { body: { currentPassword: 'Demo@12345', newPassword: 'Demo@12345' } });
  call('anon', 'GET', '/.well-known/jwks.json'); call('anon', 'GET', '/_dev/p04/keys'); call('anon', 'POST', '/_dev/p04/keys/rotate'); call('anon', 'POST', '/oauth2/token'); call('anon', 'GET', '/actuator/health');
  // admin identity
  const al = call('anon', 'POST', '/v1/admin/auth/login', { body: { email: 'admin@demo.in', password: 'Admin@12345' } });
  call('anon', 'POST', '/v1/admin/auth/mfa/setup', { body: { mfaToken: al.body.mfaToken } });
  call('anon', 'POST', '/v1/admin/auth/mfa/verify', { body: { mfaToken: al.body.mfaToken, code: '111222' } });
  call('anon', 'POST', '/v1/admin/auth/token/refresh', { body: { refreshToken: state.tokens.admin.refresh } });
  call(AD, 'GET', '/v1/admin/me');
  call(AD, 'POST', '/v1/admin/elevations', { body: { reason: 'Investigating ticket #4411', code: '123456' } }); call(AD, 'DELETE', '/v1/admin/elevations/current');
  const adms = call(AD, 'GET', '/v1/admin/admins?limit=10'); const ad1 = call(AD, 'GET', '/v1/admin/admins/' + adms.body.data[0].id);
  const inv = call(AD, 'POST', '/v1/admin/admins', { body: { email: 'ops' + Math.floor(Math.random() * 1e5) + '@demo.in', displayName: 'Ops', roles: ['SUPPORT'] } });
  call(AD, 'PATCH', '/v1/admin/admins/' + inv.body.id, { body: { roles: ['SUPPORT', 'CATALOG_EDITOR'] }, headers: { 'If-Match': '"0"' } });
  call('anon', 'POST', '/v1/admin/auth/invite/accept', { body: { token: inv.body._devInviteToken, password: 'Long-admin-pass1' } });
  call(AD, 'GET', '/v1/admin/accounts?email=asha@demo.in'); call(AD, 'GET', '/v1/admin/accounts/' + ASHA);
  // ledger
  const cards = call(A, 'GET', '/v1/cards').body.data;
  const ex = call(A, 'POST', '/v1/expenses', { body: { amountPaise: 45900, merchant: 'SWIGGY*ORDER BANGALORE', paymentMethod: 'card', userCardId: cards[0].id }, headers: K() });
  call(A, 'GET', '/v1/expenses?limit=50'); call(A, 'GET', '/v1/expenses/search?q=swiggy'); call(A, 'GET', '/v1/expenses/daily-totals?from=2026-09-01&to=2026-09-30');
  call(A, 'GET', '/v1/expenses/' + ex.body.id);
  call(A, 'PATCH', '/v1/expenses/' + ex.body.id, { body: { amountPaise: 52900, categoryPath: 'food.dining' }, headers: { 'If-Match': '"0"' } });
  call(A, 'DELETE', '/v1/expenses/' + ex.body.id); call(A, 'POST', '/v1/expenses/' + ex.body.id + ':restore');
  call(A, 'POST', '/v1/expenses:bulk', { body: { items: [{ amountPaise: 12000, merchant: 'Starbucks', clientRef: 'b1' }, { amountPaise: -5, merchant: 'bad' }, { amountPaise: 12000, merchant: 'Starbucks', clientRef: 'b1' }] }, headers: K() });
  call(A, 'GET', '/v1/categories'); call(A, 'POST', '/v1/categories', { body: { parentPath: 'food', name: 'Office lunch ' + Math.floor(Math.random() * 1000) } });
  call(A, 'GET', '/v1/merchants/autocomplete?q=zom'); call(A, 'POST', '/v1/merchants/resolve', { body: { merchant: 'UPI-ZOMATO-ORDER@okicici' } });
  const ru = call(A, 'POST', '/v1/rules', { body: { matchType: 'contains', pattern: 'gym', categoryPath: 'health.fitness', priority: 100 } }); call(A, 'GET', '/v1/rules'); call(A, 'DELETE', '/v1/rules/' + ru.body.id);
  const im = call(A, 'POST', '/v1/imports', { body: { fileName: 'hdfc-sept.csv', sizeBytes: 2048, _rows: [{ occurredAt: '2026-09-27T08:00:00Z', amount: '249', merchant: 'Netflix', clientRef: 'csv-1' }, { occurredAt: '2026-09-26T08:00:00Z', amount: 'abc', merchant: 'X' }] } });
  call(A, 'POST', '/v1/imports/' + im.body.id + ':start'); call(A, 'GET', '/v1/imports/' + im.body.id);
  call(A, 'GET', '/v1/calendar?month=2026-09'); call(A, 'GET', '/v1/summary?from=2026-09-01&to=2026-09-30&groupBy=category');
  // catalog public
  const cat = call('anon', 'GET', '/v1/catalog/cards?limit=24'); call('anon', 'GET', '/v1/catalog/cards/search?q=cashback'); call('anon', 'GET', '/v1/catalog/cards/' + cat.body.data[0].slug);
  call('anon', 'GET', '/v1/catalog/offers?category=food.delivery'); call('anon', 'GET', '/v1/catalog/categories/potential?category=food.delivery');
  // catalog admin
  call(AD, 'POST', '/v1/admin/catalog/issuers', { body: { code: 'MOCK_SUNDAR' + Math.floor(Math.random() * 1000), name: 'Sundarban Bank (MOCK)', type: 'bank' } });
  call(AD, 'GET', '/v1/admin/catalog/cards');
  const nc = call(AD, 'POST', '/v1/admin/catalog/cards', { body: { slug: 'mock-new-card-' + Math.floor(Math.random() * 1e5), name: 'MOCK_New Card', issuerCode: DB.catalog.issuers[0].code, network: 'rupay', annualFeePaise: 49900, rules: [{ id: uuid7(), categoryPath: null, merchantSlug: null, rewardType: 'cashback', rateBps: 150, pointValueMilliPaise: 0, capPaise: null, capPeriod: null, minTxnPaise: 0, excludedPaths: [], priority: 10 }] } });
  call(AD, 'GET', '/v1/admin/catalog/cards/' + nc.body.id + '/draft');
  call(AD, 'PUT', '/v1/admin/catalog/cards/' + nc.body.id + '/draft', { body: { annualFeePaise: 0 }, headers: { 'If-Match': '"0"' } });
  call(AD, 'POST', '/v1/admin/catalog/cards/' + nc.body.id + ':publish'); call(AD, 'GET', '/v1/admin/catalog/cards/' + nc.body.id + '/versions');
  call(AD, 'GET', '/v1/admin/catalog/cards/' + nc.body.id + '/draft'); call(AD, 'DELETE', '/v1/admin/catalog/cards/' + nc.body.id + '/draft');
  call(AD, 'POST', '/v1/admin/catalog/cards/' + nc.body.id + ':retire');
  const of = call(AD, 'POST', '/v1/admin/catalog/offers', { body: { cardProductId: cat.body.data[1].id, title: '5% off pharmacy', kind: 'pct', valueBps: 500, maxDiscountPaise: 20000, categoryPath: 'health' } });
  call(AD, 'POST', '/v1/admin/catalog/offers/' + of.body.id + ':withdraw');
  const src = call(AD, 'POST', '/v1/admin/ingestion/sources', { body: { url: 'https://peacock.example/cards', kind: 'html', issuerCode: 'MOCK_PEACOCK' } });
  call(AD, 'POST', '/v1/admin/ingestion/sources', { body: { url: 'https://169.254.169.254/latest/meta-data', kind: 'html' } });
  call(AD, 'GET', '/v1/admin/ingestion/sources'); call(AD, 'GET', '/v1/admin/ingestion/sources/' + src.body.id);
  call(AD, 'PATCH', '/v1/admin/ingestion/sources/' + src.body.id, { body: { enabled: false }, headers: { 'If-Match': '"0"' } });
  const rn = call(AD, 'POST', '/v1/admin/ingestion/sources/' + DB.catalog.sources[0].id + '/runs'); call(AD, 'POST', '/v1/admin/ingestion/runs', { body: {} });
  call(AD, 'GET', '/v1/admin/ingestion/runs'); call(AD, 'GET', '/v1/admin/ingestion/runs/' + rn.body.id); call(AD, 'DELETE', '/v1/admin/ingestion/sources/' + src.body.id);
  const rv = call(AD, 'GET', '/v1/admin/review?status=pending'); call(AD, 'GET', '/v1/admin/review/' + rv.body.data[0].id);
  call(AD, 'POST', '/v1/admin/review/' + rv.body.data[rv.body.data.length - 1].id + ':approve'); call(AD, 'POST', '/v1/admin/review/' + rv.body.data[rv.body.data.length - 1].id + ':approve');
  { const pend = call(AD, 'GET', '/v1/admin/review?status=pending').body.data; if (pend[0]) call(AD, 'POST', '/v1/admin/review/' + pend[0].id + ':reject', { body: { reason: 'Low confidence parse' } }); }
  // portfolio
  const nc2 = call(A, 'POST', '/v1/cards', { body: { cardProductId: DB.catalog.cards[5].id, last4: String(1000 + Math.floor(Math.random() * 8999)), statementDay: 12, dueOffsetDays: 20, creditLimitPaise: 20000000 }, headers: K() });
  call(A, 'GET', '/v1/cards/' + cards[0].id);
  call(A, 'PATCH', '/v1/cards/' + nc2.body.id, { body: { nickname: 'Travel card' }, headers: { 'If-Match': '"0"' } });
  call(A, 'GET', '/v1/cards/' + cards[0].id + '/statements');
  const tx = call(A, 'POST', '/v1/cards/' + cards[0].id + '/transactions', { body: { amountPaise: 52900, direction: 'debit', description: 'SWIGGY*ORDER BANGALORE IN' }, headers: K() });
  call(A, 'GET', '/v1/cards/' + cards[0].id + '/transactions'); call(A, 'GET', '/v1/cards/' + cards[0].id + '/transactions/' + tx.body.id);
  call(A, 'DELETE', '/v1/cards/' + nc2.body.id);
  const ep = call(A, 'POST', '/v1/emi-plans', { body: { userCardId: cards[0].id, cardTxnId: tx.body.id, description: 'Order on EMI', principalPaise: 2500000, annualRateBps: 1599, tenureMonths: 6, processingFeePaise: 19900 }, headers: K() });
  call(A, 'GET', '/v1/emi-plans'); call(A, 'GET', '/v1/emi-plans/' + ep.body.id); call(A, 'GET', '/v1/emi-plans/' + ep.body.id + '/schedule');
  call(A, 'PATCH', '/v1/emi-plans/' + ep.body.id, { body: { status: 'foreclosed' }, headers: { 'If-Match': '"0"' } }); call(A, 'DELETE', '/v1/emi-plans/' + ep.body.id);
  const sg = call(A, 'GET', '/v1/links/suggestions'); if (sg.body.data[0]) call(A, 'POST', '/v1/links/' + sg.body.data[0].id + ':confirm'); if (sg.body.data[1]) call(A, 'POST', '/v1/links/' + sg.body.data[1].id + ':reject');
  call(A, 'GET', '/v1/rewards/balance'); call(A, 'GET', '/v1/rewards/ledger');
  const pc = cards.find((c) => DB.portfolio.points.some((p) => p.userCardId === c.id && p.points > 0)) || cards[0];
  call(A, 'POST', '/v1/rewards/ledger', { body: { userCardId: pc.id, kind: 'adjust', points: 10, note: 'Goodwill' }, headers: K() });
  call(A, 'GET', '/v1/portfolio/report?period=month');
  const up = call(A, 'POST', '/v1/statements/uploads', { body: { userCardId: cards[1].id, fileName: 'statement-sep.pdf', sizeBytes: 182000 } });
  call(A, 'GET', '/v1/statements/uploads'); call(A, 'POST', '/v1/statements/uploads/' + up.body.id + ':submit', { headers: K() });
  call(A, 'GET', '/v1/statements/uploads/' + up.body.id); call(A, 'GET', '/v1/statements/uploads/' + up.body.id + '/lines');
  call(A, 'POST', '/v1/statements/uploads/' + up.body.id + ':confirm', { body: { excludeLines: [7] }, headers: K() });
  const up2 = call(A, 'POST', '/v1/statements/uploads', { body: { userCardId: cards[1].id, fileName: 'dup.pdf', sizeBytes: 1000 } }); call(A, 'POST', '/v1/statements/uploads/' + up2.body.id + ':discard');
  // calc
  const terms = { aprBps: 4284, cashAdvanceAprBps: 4284, paymentAllocationOrder: ['finance_charges', 'fees', 'retail_purchases'] };
  call(A, 'POST', '/v1/calc/emi', { body: { principalPaise: 10000000, annualRateBps: 1500, tenureMonths: 12, processingFeePaise: 19900 } });
  call(A, 'POST', '/v1/calc/ledger', { body: { terms, cycleStart: '2026-09-01', cycleEnd: '2026-09-30', transactions: [{ date: '2026-09-05', type: 'PURCHASE', amountPaise: 1500000 }], openingLots: [{ date: '2026-09-01', bucket: 'retail_purchases', remainingPaise: 4000000, aprBps: 4284 }] } });
  call(A, 'POST', '/v1/calc/minimum-due', { body: { terms: { formulaType: 'PERCENT_OF_OUTSTANDING', percentBps: 500, flatFloorPaise: 20000 }, retailCashOutstandingPaise: 2500000 } });
  call(A, 'POST', '/v1/calc/late-fee', { body: { outstandingBalancePaise: 2500000, tiers: DexEmi.LATE_TIERS, daysOverdue: 45 } });
  call(A, 'POST', '/v1/calc/utilization', { body: { spentPaise: 9500000, creditLimitPaise: 9000000, overlimitFee: { percentBps: 250, flatMinimumPaise: 50000 } } });
  call(A, 'POST', '/v1/calc/custom-fees', { body: { fees: [{ id: 'af', name: 'Annual', appliesTo: 'ONCE', amountType: 'FLAT', flatPaise: 49900 }], balancePaise: 2500000, cycleNumber: 1 } });
  call(A, 'POST', '/v1/calc/scenarios/full-payment', { body: { statementBalancePaise: 2500000, customFees: [] } });
  call(A, 'POST', '/v1/calc/scenarios/partial-payment', { body: { terms, cycleStart: '2026-09-01', cycleEnd: '2026-09-30', transactions: [{ date: '2026-09-03', type: 'PURCHASE', amountPaise: 2000000 }], paymentPaise: 800000, graceActiveThisCycle: true, nextCycleStart: '2026-10-01', nextCycleEnd: '2026-10-30', nextCycleTransactions: [{ date: '2026-10-05', type: 'PURCHASE', amountPaise: 500000 }] } });
  call(A, 'POST', '/v1/calc/scenarios/no-payment', { body: { terms, statementBalancePaise: 2500000, creditLimitPaise: 2400000, overlimitFee: { percentBps: 250, flatMinimumPaise: 50000 } } });
  call(A, 'POST', '/v1/calc/scenarios/minimum-payment', { body: { terms, startingPrincipalPaise: 2500000 } });
  call(A, 'POST', '/v1/calc/repayment-path', { body: { cards: [{ id: 'a', name: 'Saffron', balancePaise: 8500000, aprBps: 4284, minimumDuePaise: 425000 }, { id: 'b', name: 'Indus', balancePaise: 2000000, aprBps: 3600, minimumDuePaise: 100000 }], monthlyBudgetPaise: 1500000 } });
  call(A, 'POST', '/v1/calc/exit-paths', { body: { terms, currentBalancePaise: 10000000, targetCyclesOptions: [3, 6, 12] } });
  // planning
  const bd = call(A, 'POST', '/v1/budgets', { body: { name: 'Groceries', period: 'monthly', categoryPath: 'food.groceries', limitPaise: 800000 }, headers: K() });
  call(A, 'GET', '/v1/budgets'); call(A, 'GET', '/v1/budgets/status'); call(A, 'GET', '/v1/budgets/' + bd.body.id); call(A, 'GET', '/v1/budgets/' + DB.planning.budgets[1].id + '/history');
  call(A, 'PATCH', '/v1/budgets/' + bd.body.id, { body: { limitPaise: 900000 }, headers: { 'If-Match': '"0"' } }); call(A, 'DELETE', '/v1/budgets/' + bd.body.id);
  call(A, 'GET', '/v1/budgets/category-potential?category=food.delivery');
  const li = call(A, 'POST', '/v1/liabilities', { body: { kind: 'loan', lender: 'Golden test (₹8,884.88)', principalPaise: 10000000, aprBps: 1200, tenureMonths: 12 }, headers: K() });
  call(A, 'GET', '/v1/liabilities'); call(A, 'GET', '/v1/liabilities/' + li.body.id); call(A, 'GET', '/v1/liabilities/' + li.body.id + '/schedule');
  call(A, 'PATCH', '/v1/liabilities/' + li.body.id, { body: { lender: 'Golden EMI' }, headers: { 'If-Match': '"0"' } }); call(A, 'DELETE', '/v1/liabilities/' + li.body.id);
  const sc = call(A, 'POST', '/v1/scenarios', { body: { name: 'Buy a bike', monthlyIncomePaise: 12000000, adjustments: [{ kind: 'addLiability', lender: 'Bike loan', principalPaise: 15000000, aprBps: 1100, tenureMonths: 24 }] }, headers: K() });
  call(A, 'GET', '/v1/scenarios'); call(A, 'GET', '/v1/scenarios/' + sc.body.id);
  call(A, 'PATCH', '/v1/scenarios/' + sc.body.id, { body: { name: 'Buy a bike (24m)' }, headers: { 'If-Match': '"0"' } }); call(A, 'POST', '/v1/scenarios/' + sc.body.id + ':evaluate');
  // reco
  const rc = call(A, 'GET', '/v1/recommendations'); call(A, 'GET', '/v1/recommendations?cardProductIds=' + DB.catalog.cards.slice(0, 3).map((c) => c.id).join(','));
  call(A, 'GET', '/v1/recommendations/explain/' + (rc.body.recommended[0] || rc.body.notRecommended[0]).cardProductId);
  call(A, 'GET', '/v1/recommendations/wallet?size=2'); call(A, 'GET', '/v1/recommendations/best-card?category=food.delivery&amountPaise=60000');
  const tk = call(A, 'POST', '/v1/recommendations/stream-ticket'); call('anon', 'GET', '/v1/recommendations/stream?ticket=' + tk.body.ticket); call('anon', 'GET', '/v1/recommendations/stream?ticket=' + tk.body.ticket);
  // billing (ravi upgrades)
  call('anon', 'GET', '/v1/plans'); call('anon', 'GET', '/v1/plans/premium');
  state.tokens.ravi.stepUpAt = nowIso();
  call('ravi', 'POST', '/v1/subscriptions/checkout', { body: { plan: 'PREMIUM', paymentMethod: 'pm_delayed', billingState: 'KA' }, headers: K() });
  call('anon', 'POST', '/v1/webhooks/mock', { body: { id: 'evt_' + uuid7().slice(0, 8), type: 'payment.succeeded', accountId: RAVI }, headers: { 'Webhook-Signature': 't=1790000000,v1=' + hashSync('x').slice(0, 32) } });
  call('ravi', 'GET', '/v1/subscriptions/me'); call('ravi', 'GET', '/v1/entitlements/me');
  const iv = call(A, 'GET', '/v1/invoices'); call(A, 'GET', '/v1/invoices/' + iv.body.data[0].id);
  call('ravi', 'POST', '/v1/subscriptions/me:cancel');
  // commerce
  const dl = call(A, 'GET', '/v1/deals'); call('anon', 'GET', '/v1/deals/' + dl.body.data[0].id + '?spendPaise=7500000');
  call('anon', 'GET', dl.body.data[0].clickPath);
  const click = DB.commerce.clicks[DB.commerce.clicks.length - 1];
  call('anon', 'POST', '/v1/webhooks/affiliate/mock', { body: { ref: 'conv-' + uuid7().slice(0, 6), subid: click.id, status: 'approved', amountPaise: 7500000, commissionPaise: 150000, occurredAt: nowIso() }, headers: { 'Postback-Signature': 't=1790000000,v1=' + hashSync('y').slice(0, 32) } });
  call('ravi', 'GET', '/v1/ads/serve?placement=home');
  const ap = call(A, 'POST', '/v1/card-applications', { body: { cardProductId: DB.catalog.cards[8].id }, headers: K() });
  call(A, 'GET', '/v1/card-applications'); call(A, 'GET', '/v1/card-applications/' + ap.body.id);
  const nd = call(AD, 'POST', '/v1/admin/commerce/deals', { body: { title: 'Pharmacy 10% off', merchantName: 'Apollo', categoryPath: 'health.pharmacy', components: [{ kind: 'percent', valueBps: 1000, capPaise: 20000 }], referenceSpendPaise: 150000 } });
  call(AD, 'GET', '/v1/admin/commerce/deals'); call(AD, 'GET', '/v1/admin/commerce/deals/' + nd.body.id);
  call(AD, 'POST', '/v1/admin/commerce/deals/' + nd.body.id + ':status', { body: { status: 'active' }, headers: { 'If-Match': '"1"' } });
  call(AD, 'GET', '/v1/admin/commerce/card-applications'); call(AD, 'GET', '/v1/admin/commerce/card-applications/' + ap.body.id);
  call(AD, 'POST', '/v1/admin/commerce/card-applications/' + ap.body.id + ':decide', { body: { decision: 'approved' }, headers: { 'If-Match': '"1"' } });
  call(A, 'POST', '/v1/card-applications/' + DB.commerce.apps.find((a) => a.accountId === ASHA && a.status === 'in_review').id + ':withdraw');
  const cp = call(AD, 'POST', '/v1/admin/commerce/campaigns', { body: { name: 'Festive', advertiser: 'Ganga Federal (MOCK)', placements: ['home'], frequencyCap: 2 } });
  call(AD, 'GET', '/v1/admin/commerce/campaigns'); call(AD, 'GET', '/v1/admin/commerce/campaigns/' + cp.body.id);
  call(AD, 'PUT', '/v1/admin/commerce/campaigns/' + cp.body.id, { body: { weight: 2 }, headers: { 'If-Match': '"1"' } });
  const cr = call(AD, 'POST', '/v1/admin/commerce/campaigns/' + cp.body.id + '/creatives', { body: { headline: 'Festive cashback', clickUrl: 'https://ads.example/f' } });
  call(AD, 'PUT', '/v1/admin/commerce/campaigns/' + cp.body.id + '/creatives/' + cr.body.id, { body: { weight: 3 }, headers: { 'If-Match': '"1"' } });
  call(AD, 'DELETE', '/v1/admin/commerce/campaigns/' + cp.body.id); call(AD, 'GET', '/v1/admin/commerce/stats');
  // audit
  const nt = call(A, 'GET', '/v1/notifications'); call(A, 'POST', '/v1/notifications/' + nt.body.data[0].id + ':read');
  const pf = call(A, 'GET', '/v1/notification-prefs'); call(A, 'PATCH', '/v1/notification-prefs', { body: { muted: ['commerce'] }, headers: { 'If-Match': pf.headers.ETag } });
  state.tokens.asha.stepUpAt = nowIso();
  const pe = call(A, 'POST', '/v1/privacy/export', { headers: K() }); call(A, 'GET', '/v1/privacy/requests'); call(A, 'GET', '/v1/privacy/requests/' + pe.body.id);
  state.tokens.ravi.stepUpAt = nowIso(); const pdl = call('ravi', 'POST', '/v1/privacy/delete', { headers: K() });
  call(AD, 'GET', '/v1/admin/audit?limit=20'); call(AD, 'POST', '/v1/admin/audit:verify-chain');
  call(AD, 'GET', '/v1/admin/audit/privacy-requests/' + pe.body.id); call(AD, 'POST', '/v1/admin/audit/privacy-requests/' + pdl.body.id + ':resume');
  call(AD, 'GET', '/v1/admin/activity/dashboard?hours=24'); call(AD, 'POST', '/v1/admin/auth/logout');
  call(A, 'POST', '/v1/auth/logout-all'); call('ravi', 'POST', '/v1/auth/logout');
  state.persona = prev; window.SYNC_SAGA = false;
  return res;
}
