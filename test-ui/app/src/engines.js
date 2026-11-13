/* ---------- reco engine (mirrors ADR-0020 / ADR-0021 rules; mock arithmetic) ---------- */
const RECO = { savingsWeightBps: 6000, thresholdBps: 1500, ENGINE_VERSION: 3 };
const capAnnual = (r) => r.capPaise == null ? null : r.capPeriod === 'txn' ? undefined : r.capPaise * ({ statement: 12, month: 12, quarter: 4, year: 1 }[r.capPeriod] || 12);
const shareBps = (r) => { if (r.merchantSlug) return 0; const d = r.categoryPath ? r.categoryPath.split('.').length : 0; return [10000, 10000, 4000, 2000, 1000][d]; };
/** Savings for one card on a monthly spend vector {root: paise}. Rule cap is ONE cap shared across roots (A-85). */
function savingsOf(card, spend) {
  const roots = Object.entries(spend).sort((a, b) => b[1] - a[1]);
  const capLeft = {}; const lines = []; let reward = 0;
  for (const [root, monthly] of roots) {
    const cands = card.rules.filter((r) => !r.merchantSlug && (r.categoryPath == null || rootOf(r.categoryPath) === root) && !(r.excludedPaths || []).some((x) => isUnder(root, x) || rootOf(x) === root && x === root));
    const catchAll = card.rules.find((r) => r.categoryPath == null && !r.merchantSlug && !(r.excludedPaths || []).some((x) => x === root));
    let best = null;
    for (const r of cands) { const sh = shareBps(r); const val = monthly * 12 * sh / 10000 * r.rateBps / 10000 + (r.categoryPath && catchAll ? monthly * 12 * (10000 - sh) / 10000 * catchAll.rateBps / 10000 : 0); if (!best || val > best.val) best = { r, sh, val }; }
    if (!best) { lines.push({ categoryRoot: root, ruleId: null, rewardType: null, rateBps: 0, shareBps: 0, monthlySpendPaise: monthly, annualSpendConsideredPaise: 0, annualRewardPaise: 0, capBound: false, annualCapPaise: null, annualCapRemainingPaise: null, capNotConvertible: false }); continue; }
    const r = best.r, considered = Math.round(monthly * 12 * best.sh / 10000);
    let earn = Math.floor(considered * r.rateBps / 10000);
    const ca = capAnnual(r); let capBound = false, remaining = null;
    if (ca != null) { if (capLeft[r.id] == null) capLeft[r.id] = ca; remaining = capLeft[r.id]; if (earn > remaining) { earn = remaining; capBound = true; } capLeft[r.id] -= earn; }
    let rest = 0; if (r.categoryPath && catchAll && best.sh < 10000) rest = Math.floor(monthly * 12 * (10000 - best.sh) / 10000 * catchAll.rateBps / 10000);
    reward += earn + rest;
    lines.push({ categoryRoot: root, ruleId: r.id, rewardType: r.rewardType, rateBps: r.rateBps, shareBps: best.sh, monthlySpendPaise: monthly, annualSpendConsideredPaise: considered, annualRewardPaise: earn + rest, capBound, annualCapPaise: ca ?? null, annualCapRemainingPaise: remaining, capNotConvertible: ca === undefined });
  }
  const annualSpend = Object.values(spend).reduce((a, b) => a + b, 0) * 12;
  const w = card.feeWaiverSpendPaise; let prob = 0;
  if (w) prob = annualSpend >= w ? 10000 : annualSpend <= w / 2 ? 0 : Math.round((annualSpend - w / 2) / (w / 2) * 10000);
  const expectedFee = Math.round(card.annualFeePaise * (10000 - prob) / 10000);
  const ms = card.milestone; let mval = 0, reached = false, mThresh = 0;
  if (ms) { mThresh = ms.period === 'month' ? ms.spendThresholdPaise : ms.period === 'quarter' ? ms.spendThresholdPaise / 3 : ms.spendThresholdPaise / 12; reached = annualSpend / 12 >= mThresh; mval = reached ? ms.benefitValuePaise * (ms.period === 'month' ? 12 : ms.period === 'quarter' ? 4 : 1) : 0; }
  return { reward, expectedFee, prob, milestoneValue: mval, milestoneReached: reached, monthlyThreshold: Math.round(mThresh), savings: reward - expectedFee + mval, lines, annualSpend };
}
function fitOf(card, spend) {
  const roots = Object.keys(spend); if (!roots.length) return 0;
  let dot = 0, a = 0, b = 0;
  for (const r of roots) { const rate = Math.max(0, ...card.rules.filter((x) => !x.merchantSlug && (x.categoryPath == null || rootOf(x.categoryPath) === r) && !(x.excludedPaths || []).includes(r)).map((x) => x.rateBps * shareBps(x) / 10000)); dot += rate * spend[r]; a += rate * rate; b += spend[r] * spend[r]; }
  return a && b ? Math.round(dot / Math.sqrt(a * b) * 10000) : 0;
}
function reasonsFor(card, s, held) {
  const out = []; const top = [...s.lines].sort((x, y) => y.annualRewardPaise - x.annualRewardPaise)[0];
  if (!Object.keys(s.lines).length) out.push('No spend recorded yet, so this is based on the card\'s terms alone.');
  if (top && top.annualRewardPaise > 0) out.push(`Earns ${rs(top.annualRewardPaise, { whole: 1 })}/yr on ${top.categoryRoot} at ${pct(top.rateBps)}${top.shareBps < 10000 ? ` on ${pct(top.shareBps, 0)} of it` : ''}`);
  const cb = s.lines.filter((l) => l.capBound); if (cb.length) out.push(`Cap binds on ${cb.map((l) => l.categoryRoot).join(', ')}`);
  if (card.annualFeePaise) out.push(s.prob >= 10000 ? `Fee ${rs(card.annualFeePaise, { whole: 1 })} waived at your spend` : `Expected fee ${rs(s.expectedFee, { whole: 1 })} (waiver ${pct(s.prob, 0)} likely)`);
  if (s.milestoneReached) out.push(`Milestone reached: +${rs(s.milestoneValue, { whole: 1 })}/yr`);
  if (held) out.push('You already hold this card');
  return out;
}
function stateHash(acct) {
  const sp = spendVector(acct); const held = DB.portfolio.cards.filter((c) => c.accountId === acct && c.status === 'active').map((c) => c.cardProductId).sort();
  const cat = DB.catalog.cards.filter((c) => c.status === 'published').map((c) => c.id + ':' + c.currentVersion).join(',');
  return hashSync(JSON.stringify([RECO.ENGINE_VERSION, sp, held, cat])).slice(0, 24);
}
function scoreAll(acct, ids) {
  const spend = spendVector(acct); const heldIds = new Set(DB.portfolio.cards.filter((c) => c.accountId === acct && c.status === 'active').map((c) => c.cardProductId));
  const cands = DB.catalog.cards.filter((c) => c.status === 'published' && (!ids || ids.includes(c.id)));
  const scored = cands.map((c) => { const s = savingsOf(c, spend); return { c, s, fit: fitOf(c, spend) }; });
  const best = Math.max(0, ...scored.map((x) => x.s.savings));
  const rows = scored.map(({ c, s, fit }) => {
    const sv = best > 0 ? Math.max(0, Math.round(s.savings / best * 10000)) : 0;
    const score = Math.round((RECO.savingsWeightBps * sv + (10000 - RECO.savingsWeightBps) * fit) / 10000);
    return { cardProductId: c.id, slug: c.slug, name: c.name, issuerCode: c.issuerCode, held: heldIds.has(c.id), annualSavingsPaise: s.savings, savingsBps: sv, fitBps: fit, scoreBps: score, reasons: reasonsFor(c, s, heldIds.has(c.id)), _s: s };
  }).sort((a, b) => b.scoreBps - a.scoreBps || b.annualSavingsPaise - a.annualSavingsPaise);
  const strip = ({ _s, ...r }) => r;
  return { window: '3m', monthlySpendPaise: Object.values(spend).reduce((a, b) => a + b, 0), spendByRoot: spend, savingsWeightBps: RECO.savingsWeightBps, thresholdBps: RECO.thresholdBps,
    recommended: rows.filter((r) => r.scoreBps >= RECO.thresholdBps && r.annualSavingsPaise > 0).map(strip), notRecommended: rows.filter((r) => !(r.scoreBps >= RECO.thresholdBps && r.annualSavingsPaise > 0)).map(strip) };
}
/** cached read: recompute only when the inputs' state hash changed (A-83: catalog changes never fan out) */
function recoRead(acct) {
  const h = stateHash(acct); const cur = DB.reco.results[acct];
  if (cur && cur.stateHash === h) return { ...cur.view, fresh: true, computedAt: cur.computedAt, stateHash: h, recomputed: false };
  const view = scoreAll(acct); const computedAt = nowIso();
  DB.reco.results[acct] = { stateHash: h, view, computedAt };
  DB.reco.history.unshift({ accountId: acct, stateHash: h, computedAt, top: view.recommended[0] && view.recommended[0].slug });
  const s = recState(acct); s.dirty = false; s.computedAt = computedAt;
  emit('dexter.reco.recommendation.v1', 'reco.recommendation.updated', acct, { stateHash: h, top: view.recommended.slice(0, 3).map((r) => r.slug) });
  pushStream(acct, 'recommendation.updated', h, view);
  return { ...view, fresh: true, computedAt, stateHash: h, recomputed: true };
}
/** debounced sweeper: claims dirty accounts quiet ≥3 s (SKIP LOCKED in Postgres), recomputes, compares hashes */
function sweep(quietMs = 0) {
  const out = { claimed: 0, recomputed: 0, changed: 0, failed: 0 };
  for (const [acct, s] of Object.entries(DB.reco.recState)) {
    if (!s.dirty || (s._wallDirty && Date.now() - Date.parse(s._wallDirty) < quietMs)) continue;
    out.claimed++; s.dirty = false;
    const before = DB.reco.results[acct] && DB.reco.results[acct].stateHash;
    const r = recoRead(acct); out.recomputed++; if (r.stateHash !== before) out.changed++;
  }
  return out;
}
function explain(acct, cid) {
  const c = cardById(cid); if (!c) fail(404, 'NOT_FOUND', 'Card product');
  const spend = spendVector(acct); const s = savingsOf(c, spend); const all = scoreAll(acct);
  const row = [...all.recommended, ...all.notRecommended].find((r) => r.cardProductId === cid);
  const rateByRoot = {}; for (const l of s.lines) rateByRoot[l.categoryRoot] = l.rateBps;
  return { cardProductId: c.id, slug: c.slug, name: c.name, issuerCode: c.issuerCode, held: row.held, window: '3m', monthlySpendPaise: all.monthlySpendPaise, annualRewardPaise: s.reward, annualSavingsPaise: s.savings, savingsBps: row.savingsBps, fitBps: row.fitBps, scoreBps: row.scoreBps,
    recommended: all.recommended.some((r) => r.cardProductId === cid), fee: { annualFeePaise: c.annualFeePaise, waiverSpendPaise: c.feeWaiverSpendPaise, waiverProbabilityBps: s.prob, expectedFeePaise: s.expectedFee },
    milestone: { annualValuePaise: s.milestoneValue, reached: s.milestoneReached, monthlyThresholdPaise: s.monthlyThreshold, monthlyShortfallPaise: Math.max(0, s.monthlyThreshold - all.monthlySpendPaise) }, lines: s.lines, rewardRateBpsByRoot: rateByRoot, reasons: row.reasons, computedAt: nowIso() };
}
/** wallet optimizer: beam top-15 ∪ held, exhaustive 1/2/3 combos, each root routed to exactly ONE card */
function wallet(acct, maxSize = 3) {
  const spend = spendVector(acct); const roots = Object.keys(spend);
  const all = scoreAll(acct); const ranked = [...all.recommended, ...all.notRecommended];
  const heldIds = new Set(ranked.filter((r) => r.held).map((r) => r.cardProductId));
  const beam = [...new Set([...ranked.slice(0, 15).map((r) => r.cardProductId), ...heldIds])].map(cardById);
  const sub = (keys) => Object.fromEntries(keys.map((k) => [k, spend[k]]));
  const valueOf = (combo) => {
    // exhaustive routing when ≤ 7 roots, else greedy by marginal (exact:false)
    const exact = Math.pow(combo.length, roots.length) <= 2200;
    let bestV = -Infinity, bestR = null;
    const evalR = (assign) => { let v = 0; const seats = combo.map((c, i) => { const ks = roots.filter((r, j) => assign[j] === i); const s = savingsOf(c, sub(ks)); v += s.savings; return { c, ks, s }; }); if (v > bestV) { bestV = v; bestR = seats; } };
    if (exact) { const n = combo.length, tot = Math.pow(n, roots.length); for (let x = 0; x < tot; x++) { const a = []; let y = x; for (let j = 0; j < roots.length; j++) { a.push(y % n); y = Math.floor(y / n); } evalR(a); } }
    else { const a = roots.map((r) => { let bi = 0, bv = -1; combo.forEach((c, i) => { const v = savingsOf(c, sub([r])).reward; if (v > bv) { bv = v; bi = i; } }); return bi; }); evalR(a); }
    return { v: bestV, seats: bestR, exact };
  };
  const bySize = []; let prev = 0;
  for (let k = 1; k <= maxSize; k++) {
    let best = null;
    const rec = (start, combo) => { if (combo.length === k) { const r = valueOf(combo); if (!best || r.v > best.v) best = r; return; } for (let i = start; i < beam.length; i++) rec(i + 1, [...combo, beam[i]]); };
    rec(0, []);
    if (!best) break;
    const routing = {}; best.seats.forEach((s) => s.ks.forEach((r) => (routing[r] = s.c.slug)));
    bySize.push({ size: k, seats: best.seats.map((s) => ({ cardProductId: s.c.id, slug: s.c.slug, name: s.c.name, held: heldIds.has(s.c.id), categoryRoots: s.ks, annualValuePaise: s.s.savings, annualRewardPaise: s.s.reward, expectedFeePaise: s.s.expectedFee })),
      annualValuePaise: best.v, annualRewardPaise: best.seats.reduce((a, s) => a + s.s.reward, 0), annualFeePaise: best.seats.reduce((a, s) => a + s.s.expectedFee, 0), marginalValuePaise: k === 1 ? 0 : best.v - prev, exact: best.exact, routing });
    prev = best.v;
  }
  return { bySize, considered: DB.catalog.cards.length, afterPruning: DB.catalog.cards.length, inBeam: beam.length, dropped: [], note: 'Dominance pruning is reported, not applied (ADR-0021 §3)' };
}
/** best card now among HELD cards: rule reward capped by this period's headroom + offers + milestone proximity */
function bestCard(acct, { merchantId, category, amountPaise }) {
  const cat = category || (merchantId && (merchantById(merchantId) || {}).category) || 'general';
  const mo = monthOf(today());
  const picks = DB.portfolio.cards.filter((c) => c.accountId === acct && c.status === 'active').map((c) => {
    const prod = cardById(c.cardProductId); const e = earnFor(c, cat, merchantId, amountPaise); const r = e.rule;
    let headroom = null, exhausted = false, reward = e.valuePaise;
    if (r && r.capPaise != null && r.capPeriod !== 'txn') {
      let earned = 0; for (const [k, v] of DB.reco.cardPeriod) { const [a, uc, m, root] = k.split('|'); if (a === acct && uc === c.id && m === mo && (r.categoryPath == null || root === rootOf(r.categoryPath))) earned += Math.floor(v * r.rateBps / 10000); }
      headroom = Math.max(0, r.capPaise - earned); exhausted = headroom === 0; reward = Math.min(reward, headroom);
    }
    const offers = DB.catalog.offers.filter((o) => o.status === 'active' && o.cardProductId === c.cardProductId && amountPaise >= (o.minSpendPaise || 0) && ((o.merchantId && o.merchantId === merchantId) || (!o.merchantId && o.categoryPath && isUnder(cat, o.categoryPath))))
      .map((o) => ({ offerId: o.id, title: o.title, kind: o.kind, valuePaise: o.kind === 'pct' ? Math.min(Math.floor(amountPaise * o.valueBps / 10000), o.maxDiscountPaise || Infinity) : Math.min(o.valuePaise, o.maxDiscountPaise || o.valuePaise), stackable: o.stackable }));
    offers.sort((a, b) => b.valuePaise - a.valuePaise);
    const offerPaise = offers.length ? offers.filter((o, i) => i === 0 || o.stackable).reduce((a, o) => a + o.valuePaise, 0) : 0;
    let milestonePaise = 0; const ms = prod && prod.milestone; let periodSpend = 0;
    for (const [k, v] of DB.reco.cardPeriod) { const [a, uc, m] = k.split('|'); if (a === acct && uc === c.id && m === mo) periodSpend += v; }
    if (ms && ms.period === 'month' && periodSpend < ms.spendThresholdPaise && periodSpend + amountPaise >= ms.spendThresholdPaise) milestonePaise = ms.benefitValuePaise;
    const reasons = [];
    if (r) reasons.push(`${pct(e.rateBps)} ${e.type} via ${r.categoryPath || 'catch-all'} rule`);
    if (exhausted) reasons.push('Cap for this period already used — earns nothing more here');
    else if (headroom != null && e.valuePaise > headroom) reasons.push(`Cap leaves only ${rs(headroom)} this period`);
    if (offers.length) reasons.push(offers.map((o) => o.title).join(' + '));
    if (milestonePaise) reasons.push(`This purchase crosses the ${rs(ms.spendThresholdPaise, { whole: 1 })} monthly milestone`);
    return { userCardId: c.id, cardProductId: c.cardProductId, slug: prod && prod.slug, name: c.nickname + ' ··' + c.last4, ruleId: r ? r.id : null, rateBps: e.rateBps, rewardPaise: reward, offerPaise, milestonePaise, totalPaise: reward + offerPaise + milestonePaise, headroomPaise: headroom, capExhausted: exhausted, periodSpendPaise: periodSpend, offers, reasons };
  }).sort((a, b) => b.totalPaise - a.totalPaise);
  return { amountPaise, merchantId: merchantId || null, categoryPath: cat, picks };
}
/* ---------- SSE (Redis pub/sub fan-out in prod; one in-page channel here) ---------- */
const STREAMS = [];
function pushStream(acct, event, id, data) { for (const s of STREAMS) if (s.acct === acct && s.open) s.onEvent({ event, id, data }); }

/* ---------- commerce: per-component capped savings + ranking ---------- */
function dealSavings(deal, spend) {
  const lines = deal.components.map((c) => {
    let sv = 0; if (c.minSpendPaise && spend < c.minSpendPaise) sv = 0;
    else if (c.kind === 'percent') sv = Math.min(Math.floor(spend * c.valueBps / 10000), c.capPaise ?? Infinity);
    else sv = Math.min(c.valuePaise, spend);
    return { component: c, savingPaise: sv };
  });
  return { spendPaise: spend, lines, totalPaise: Math.min(spend, lines.reduce((a, l) => a + l.savingPaise, 0)) };
}
function dealFeed(acct, category) {
  const held = new Set(acct ? DB.portfolio.cards.filter((c) => c.accountId === acct && c.status === 'active').map((c) => c.cardProductId) : []);
  const act = DB.commerce.deals.filter((d) => d.status === 'active' && (!category || isUnder(d.categoryPath, category))).slice(0, 500);
  const sv = act.map((d) => dealSavings(d, d.referenceSpendPaise).totalPaise); const best = Math.max(1, ...sv);
  return act.map((d, i) => {
    const match = d.eligibleCards.length === 0 ? 5000 : d.eligibleCards.some((e) => held.has(e.cardProductId)) ? 10000 : 0;
    const sn = Math.round(sv[i] / best * 10000), sp = Math.round(d.sponsorWeight * 10000);
    const score = Math.floor((48 * match + 32 * sn + 20 * sp) / 100);
    return { id: d.id, merchantId: d.merchantId, merchantName: d.merchantName, title: d.title, description: d.description, categoryPath: d.categoryPath, components: d.components, eligibleCards: d.eligibleCards, referenceSpendPaise: d.referenceSpendPaise, savingsPaise: sv[i],
      cardMatch: match === 10000 ? 'held' : match === 5000 ? 'open' : 'other', sponsored: d.sponsorWeight > 0, scoreBps: score, priority: d.priority, validFrom: d.validFrom, validTo: d.validTo, clickPath: '/r/' + signClick('deal', d.id, acct), _parts: { match, sn, sp } };
  }).sort((a, b) => b.scoreBps - a.scoreBps || b.priority - a.priority || (a.id < b.id ? -1 : 1));
}
function signClick(kind, target, acct) { const p = btoa(JSON.stringify({ v: 1, k: kind, t: target, a: acct || null, e: Date.parse(nowIso()) + 7 * 86400000, n: Math.random().toString(36).slice(2, 8) })).replace(/=/g, ''); return p + '.' + hashSync(p).slice(0, 10); }
function verifyClick(tok) { const [p, s] = tok.split('.'); if (!p || hashSync(p).slice(0, 10) !== s) return { outcome: 'INVALID' }; const c = JSON.parse(atob(p)); if (c.e < Date.parse(nowIso())) return { outcome: 'EXPIRED', c }; return { outcome: 'OK', c }; }

/* ---------- planning: status + projection ---------- */
function budgetStatus(b, day = today()) {
  const per = periodFor({ ...b, status: 'active' }, day) || [b.startsOn, b.endsOn || day];
  const row = DB.planning.spend.get(b.id + '|' + per[0]) || { spentPaise: 0 };
  const dIn = daysBetween(per[0], per[1] > '9000' ? addDays(per[0], 29) : per[1]) + 1, el = Math.min(dIn, daysBetween(per[0], day) + 1);
  const proj = Math.round(row.spentPaise / el * dIn);
  return { periodStart: per[0], periodEnd: per[1], limitPaise: b.limitPaise, spentPaise: row.spentPaise, remainingPaise: Math.max(0, b.limitPaise - row.spentPaise), usedBps: Math.round(row.spentPaise / b.limitPaise * 10000), overLimit: row.spentPaise > b.limitPaise,
    projectedSpendPaise: proj, projectedOverspendPaise: Math.max(0, proj - b.limitPaise), daysElapsed: el, daysInPeriod: dIn, thresholdsCrossed: b.alertThresholds.filter((t) => row.spentPaise * 100 >= t * b.limitPaise) };
}
