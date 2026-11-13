/* DexCalc — exact JS ports of libs/portfolio-calc (BigInt decimal, same rounding points as the Java engines).
   Verified against the real Java engines by verify.js (ref.json produced by RefGrid.java). */
const DexCalc = (() => {
  const B = BigInt;
  const P10 = []; for (let i = 0; i < 200; i++) P10.push(10n ** B(i));
  const pow10 = (k) => (k < 200 ? P10[k] : 10n ** B(k));
  const D = (u, s = 0) => ({ u: B(u), s });
  const ZERO = D(0), ONE = D(1), HUNDRED = D(100);
  const HU = 'HALF_UP', HE = 'HALF_EVEN', CEIL = 'CEILING';
  const abs = (x) => (x < 0n ? -x : x);
  function roundDiv(n, d, mode) { // d > 0
    if (n < 0n) {
      const m = mode === CEIL ? 'FLOOR' : mode === 'FLOOR' ? CEIL : mode;
      return -roundDiv(-n, d, m);
    }
    const q = n / d, r = n % d;
    if (r === 0n) return q;
    switch (mode) {
      case HU: return 2n * r >= d ? q + 1n : q;
      case HE: { const c = 2n * r; return c > d ? q + 1n : c < d ? q : (q % 2n === 1n ? q + 1n : q); }
      case CEIL: return q + 1n;
      case 'FLOOR': return q;
      default: throw new Error('mode ' + mode);
    }
  }
  const setScale = (x, s, mode) => (s >= x.s ? D(x.u * pow10(s - x.s), s) : D(roundDiv(x.u, pow10(x.s - s), mode), s));
  const align = (a, b) => { const s = Math.max(a.s, b.s); return [a.u * pow10(s - a.s), b.u * pow10(s - b.s), s]; };
  const add = (a, b) => { const [x, y, s] = align(a, b); return D(x + y, s); };
  const sub = (a, b) => { const [x, y, s] = align(a, b); return D(x - y, s); };
  const mul = (a, b) => D(a.u * b.u, a.s + b.s);
  const cmp = (a, b) => { const [x, y] = align(a, b); return x < y ? -1 : x > y ? 1 : 0; };
  const min = (a, b) => (cmp(a, b) <= 0 ? a : b);
  const max = (a, b) => (cmp(a, b) >= 0 ? a : b);
  const digits = (u) => (u === 0n ? 1 : abs(u).toString().length);
  function divScale(a, b, scale, mode) {
    const k = scale - a.s + b.s;
    let n = a.u, d = b.u;
    if (k >= 0) n = n * pow10(k); else d = d * pow10(-k);
    if (d < 0n) { n = -n; d = -d; }
    return D(roundDiv(n, d, mode), scale);
  }
  function roundMC(x, prec, mode) {
    const dg = digits(x.u);
    if (dg <= prec) return x;
    const drop = dg - prec;
    let u = roundDiv(x.u, pow10(drop), mode), s = x.s - drop;
    if (digits(u) > prec) { u = u / 10n; s -= 1; }
    return D(u, s);
  }
  const mulMC = (a, b, prec, mode) => roundMC(mul(a, b), prec, mode);
  function divMC(a, b, prec, mode) {
    if (a.u === 0n) return D(0, 0);
    const neg = (a.u < 0n) !== (b.u < 0n);
    const A = D(abs(a.u), a.s), Bv = D(abs(b.u), b.s);
    const mag = (digits(A.u) - A.s) - (digits(Bv.u) - Bv.s);
    const S = prec + 3 - mag;
    const k = S - A.s + Bv.s;
    let n = A.u, d = Bv.u;
    if (k >= 0) n = n * pow10(k); else d = d * pow10(-k);
    const q = n / d, r = n % d;
    const qs = q * 10n + (r !== 0n ? 1n : 0n);
    const res = roundMC(D(qs, S + 1), prec, mode);
    return neg ? D(-res.u, res.s) : res;
  }
  function powMC(x, n, prec, mode) { // java.math.BigDecimal.pow(int, MathContext)
    if (n === 0) return ONE;
    let mag = Math.abs(n);
    const work = prec + String(mag).length + 1;
    let acc = ONE, seen = false;
    for (let i = 1; ; i++) {
      mag = (mag << 1) | 0;
      if (mag < 0) { seen = true; acc = mulMC(acc, x, work, mode); }
      if (i === 31) break;
      if (seen) acc = mulMC(acc, acc, work, mode);
    }
    return roundMC(acc, prec, mode);
  }
  const round2 = (x) => setScale(x, 2, HU);
  const rupees = (p) => D(p, 2);
  const paise = (x) => Number(round2(x).u);
  const percent = (bps) => D(bps, 2);
  const isPos = (x) => x.u > 0n;
  const GST_RATE = D(18, 2);
  const gst = (x) => round2(mul(x, GST_RATE));
  const fmt = (x) => { const v = round2(x); const neg = v.u < 0n; const t = abs(v.u).toString().padStart(3, '0');
    return (neg ? '-' : '') + t.slice(0, -2) + '.' + t.slice(-2); };

  /* ---------------- EmiMath ---------------- */
  const MC = 34;
  const monthlyRate = (bps) => divMC(divMC(D(bps), D(10000), MC, HE), D(12), MC, HE);
  function emi(principalPaise, annualRateBps, months) {
    if (principalPaise <= 0 || months < 1 || annualRateBps < 0) throw new Error('principal > 0, months >= 1, rate >= 0');
    const p = D(principalPaise);
    if (annualRateBps === 0) return Number(divScale(p, D(months), 0, HU).u);
    const r = monthlyRate(annualRateBps);
    const f = powMC(add(ONE, r), months, MC, HE);
    return Number(setScale(divMC(mulMC(mulMC(p, r, MC, HE), f, MC, HE), sub(f, ONE), MC, HE), 0, HU).u);
  }
  function emiSchedule(principalPaise, annualRateBps, months, gstBps, processingFeePaise, firstDue) {
    const e = emi(principalPaise, annualRateBps, months);
    const r = annualRateBps === 0 ? ZERO : monthlyRate(annualRateBps);
    const g = divMC(D(gstBps), D(10000), MC, HE);
    let out = principalPaise, ti = 0, tg = 0; const rows = [];
    for (let k = 1; k <= months; k++) {
      const interest = Number(setScale(mulMC(D(out), r, MC, HE), 0, HE).u);
      const principal = k === months ? out : Math.min(out, Math.max(0, e - interest));
      const fee = k === 1 ? processingFeePaise : 0;
      const gg = Number(setScale(mulMC(D(interest + fee), g, MC, HE), 0, HE).u);
      out -= principal; ti += interest; tg += gg;
      rows.push({ number: k, dueDate: firstDue ? addMonths(firstDue, k - 1) : null, principalPaise: principal,
        interestPaise: interest, gstPaise: gg, totalPaise: principal + interest + fee + gg, outstandingAfterPaise: out });
    }
    return { emiPaise: e, totalInterestPaise: ti, totalGstPaise: tg, installments: rows };
  }
  function addMonths(iso, m) { // java LocalDate.plusMonths semantics (clamp to month end)
    const [y, mo, d] = iso.split('-').map(Number);
    const t = y * 12 + (mo - 1) + m; const ny = Math.floor(t / 12), nm = t % 12;
    const last = new Date(Date.UTC(ny, nm + 1, 0)).getUTCDate();
    return `${ny}-${String(nm + 1).padStart(2, '0')}-${String(Math.min(d, last)).padStart(2, '0')}`;
  }

  /* ---------------- LedgerEngine ---------------- */
  const DAYS = D(365);
  const lot = (day, bucket, remaining, apr, ref = null) => ({ day, bucket, remaining, apr, ref });
  const open = (l) => isPos(l.remaining);
  function interestForDay(lots, day) {
    let s = ZERO;
    for (const l of lots) if (open(l) && l.day <= day)
      s = add(s, divScale(mul(l.remaining, divScale(l.apr, HUNDRED, 10, HU)), DAYS, 10, HU));
    return s;
  }
  function waterfall(lots, payment, order) {
    let rem = payment;
    for (const b of order) {
      if (!isPos(rem)) break;
      const bl = lots.map((l, i) => [l, i]).filter(([l]) => l.bucket === b && open(l))
        .sort((x, y) => x[0].day - y[0].day || x[1] - y[1]).map((x) => x[0]);
      for (const l of bl) {
        if (!isPos(rem)) break;
        const pay = min(l.remaining, rem);
        l.remaining = round2(sub(l.remaining, pay));
        rem = round2(sub(rem, pay));
      }
    }
    return rem;
  }
  const sumRemaining = (lots) => lots.reduce((a, l) => add(a, l.remaining), ZERO);
  /** txns: [{day, type: PURCHASE|PAYMENT|REFUND_SPECIFIC, amountPaise, bucket, ref, originalRef}] */
  function accrue(txns, terms, start, end, openingLots, graceActive) {
    const lots = openingLots.map((l) => ({ ...l }));
    const retail = percent(terms.aprBps), cash = percent(terms.cashAdvanceAprBps);
    let total = ZERO;
    for (let day = start; day <= end; day++) {
      for (const t of txns.filter((x) => x.day === day)) {
        const amt = rupees(t.amountPaise);
        if (t.type === 'PURCHASE') {
          const bucket = t.bucket || 'retail_purchases';
          const apr = bucket === 'cash_advances' ? cash : graceActive ? ZERO : retail;
          lots.push(lot(day, bucket, amt, apr, t.ref || null));
        } else if (t.type === 'PAYMENT') waterfall(lots, amt, terms.order);
        else if (t.type === 'REFUND_SPECIFIC') {
          const tg = lots.find((l) => l.ref != null && l.ref === t.originalRef && open(l));
          if (tg) tg.remaining = round2(sub(tg.remaining, min(tg.remaining, amt)));
          else waterfall(lots, amt, terms.order);
        }
      }
      total = add(total, interestForDay(lots, day));
    }
    return { totalInterest: round2(total), closingLots: lots.filter(open) };
  }

  /* ---------------- MinimumDue ---------------- */
  const roundUpToNearest = (v, nearest) => (nearest == null || nearest.u === 0n ? round2(v) : mul(divScale(v, nearest, 0, CEIL), nearest));
  const percentOf = (amt, bps) => divScale(mul(amt, percent(bps)), HUNDRED, 10, HU);
  function minDue(t, x) { // x: {retailCashOutstandingPaise, financeChargesPaise, emiAmountPaise, feesAndChargesPaise, gstAmountPaise, overlimitAmountPaise, totalAmountDuePaise}
    const out = rupees(x.retailCashOutstandingPaise), fc = rupees(x.financeChargesPaise);
    switch (t.formulaType) {
      case 'FLAT': return round2(rupees(t.flatAmountPaise));
      case 'PERCENT_OF_OUTSTANDING': return round2(max(percentOf(out, t.percentBps), rupees(t.flatFloorPaise)));
      case 'HIGHER_OF': return round2(max(percentOf(out, t.percentBps), rupees(t.flatAmountPaise)));
      case 'FINANCE_CHARGE_CONDITIONAL': {
        const totalDue = rupees(x.totalAmountDuePaise), floor = rupees(t.flatFloorPaise);
        if (isPos(totalDue) && cmp(totalDue, floor) <= 0) return round2(totalDue);
        const rate = divScale(percent(t.retailCashPercentBps), HUNDRED, 10, HU);
        const nearest = t.roundUpToNearestPaise == null ? null : rupees(t.roundUpToNearestPaise);
        const blended = roundUpToNearest(mul(rate, add(out, fc)), nearest);
        const base = add(add(rupees(x.gstAmountPaise), rupees(x.emiAmountPaise)), rupees(x.feesAndChargesPaise));
        const computed = cmp(blended, fc) > 0 ? add(base, blended) : add(add(base, fc), roundUpToNearest(mul(rate, out), nearest));
        return round2(max(max(computed, floor), rupees(x.overlimitAmountPaise)));
      }
    }
    throw new Error('formulaType');
  }
  const minDuePaise = (t, x) => paise(minDue(t, { emiAmountPaise: 0, feesAndChargesPaise: 0, gstAmountPaise: 0,
    overlimitAmountPaise: 0, totalAmountDuePaise: 0, financeChargesPaise: 0, ...x }));

  /* ---------------- Utilization / over-limit / late fee ---------------- */
  function utilization(spentPaise, limitPaise) {
    if (limitPaise == null) return { creditLimitConfigured: false, creditLimitPaise: null, spentPaise, availableCreditPaise: null, utilizationBps: null, overLimit: false, overLimitPaise: 0 };
    if (limitPaise <= 0) throw new Error('a configured credit limit must be greater than zero');
    const s = rupees(spentPaise), l = rupees(limitPaise);
    const over = round2(max(sub(s, l), ZERO));
    const ut = mul(divScale(s, l, 10, HU), HUNDRED);
    return { creditLimitConfigured: true, creditLimitPaise: limitPaise, spentPaise, availableCreditPaise: paise(max(sub(l, s), ZERO)),
      utilizationBps: Number(setScale(ut, 2, HU).u), overLimit: isPos(over), overLimitPaise: paise(over) };
  }
  function overlimitFeeD(over, terms) {
    if (over == null || !isPos(over) || terms == null) return ZERO;
    const pct = round2(divScale(mul(over, percent(terms.percentBps)), HUNDRED, 10, HU));
    return round2(max(pct, rupees(terms.flatMinimumPaise)));
  }
  const overlimitFee = (overPaise, terms) => paise(overlimitFeeD(rupees(overPaise), terms));
  function lateFee(outPaise, tiers) {
    if (outPaise <= 0) return 0;
    const t = tiers.find((t) => outPaise >= t.minOutstandingPaise && (t.maxOutstandingPaise == null || outPaise <= t.maxOutstandingPaise));
    return t ? t.lateFeePaise : 0;
  }
  function delinquency(days) {
    if (days <= 0) return { stage: 'CURRENT', label: 'No overdue amount', reportedToBureaus: false, severelyDamaging: false };
    if (days <= 30) return { stage: 'SMA_0', label: 'Up to 30 days overdue — flagged as Special Mention Account', reportedToBureaus: true, severelyDamaging: false };
    if (days <= 60) return { stage: 'SMA_1', label: '30-60 days overdue — escalated Special Mention Account', reportedToBureaus: true, severelyDamaging: false };
    if (days <= 90) return { stage: 'SMA_2', label: '60-90 days overdue — final stage before NPA classification', reportedToBureaus: true, severelyDamaging: false };
    return { stage: 'NPA', label: '90+ days overdue — classified as Non-Performing Asset', reportedToBureaus: true, severelyDamaging: true };
  }

  /* ---------------- Simulators ---------------- */
  function flat(terms, start, payment, target, cycleLen) {
    const apr = percent(terms.aprBps);
    let lots = [lot(0, 'retail_purchases', start, apr)], cs = 0, ti = ZERO, tg = ZERO;
    for (let c = 1; c <= target; c++) {
      const opening = round2(sumRemaining(lots));
      if (!isPos(opening)) return { cleared: c - 1, ti, tg, feasible: true };
      const ce = cs + cycleLen - 1;
      const fc = accrue([], terms, cs, ce, lots, false).totalInterest;
      const g = gst(fc);
      ti = round2(add(ti, fc)); tg = round2(add(tg, g));
      const owed = round2(add(add(opening, fc), g));
      const pay = min(payment, owed);
      const L = lots.map((l) => ({ ...l }));
      L.push(lot(ce, 'finance_charges', round2(add(fc, g)), apr));
      waterfall(L, pay, terms.order);
      lots = L.filter(open).map((l) => ({ ...l, day: ce }));
      cs += cycleLen;
      if (cmp(sumRemaining(lots), ONE) < 0) return { cleared: c, ti, tg, feasible: true };
    }
    return { cleared: null, ti, tg, feasible: false };
  }
  function simulateFlatPayment(terms, startPaise, payPaise, target, cycleLen = 30) {
    const r = flat(terms, rupees(startPaise), rupees(payPaise), target, cycleLen);
    return { clearedByCycle: r.cleared, totalInterestPaise: paise(r.ti), totalGstPaise: paise(r.tg), feasible: r.feasible };
  }
  function search(terms, bal, target, cycleLen) {
    let lo = round2(divScale(bal, D(target), 10, HU)), hi = round2(mul(bal, D(15, 1))), best = null;
    for (let i = 0; i < 25; i++) {
      const mid = round2(divScale(add(lo, hi), D(2), 10, HU));
      const r = flat(terms, bal, mid, target, cycleLen);
      if (r.feasible) { best = { pay: mid, cleared: r.cleared, ti: r.ti, tg: r.tg }; hi = mid; } else lo = mid;
      if (cmp(sub(hi, lo), ONE) < 0) break;
    }
    return best;
  }
  function findMinimumFlatPayment(terms, balPaise, target, cycleLen = 30) {
    const f = search(terms, rupees(balPaise), target, cycleLen);
    return f && { paymentPaise: paise(f.pay), clearedByCycle: f.cleared, totalInterestPaise: paise(f.ti), totalGstPaise: paise(f.tg) };
  }
  function minimumOnly(terms, md, startPaise, cycleLen = 30, maxCycles = 360, limitPaise = null, olTerms = null) {
    const apr = percent(terms.aprBps);
    let lots = [lot(0, 'retail_purchases', rupees(startPaise), apr)], n = 0, cs = 0; const table = [];
    while (isPos(sumRemaining(lots)) && n < maxCycles) {
      n++;
      const ce = cs + cycleLen - 1;
      const opening = round2(sumRemaining(lots));
      const fc = accrue([], terms, cs, ce, lots, false).totalInterest, g = gst(fc);
      let over = ZERO, olf = ZERO;
      if (limitPaise != null) {
        over = rupees(utilization(paise(opening), limitPaise).overLimitPaise);
        if (isPos(over) && olTerms) olf = overlimitFeeD(over, olTerms);
      }
      const olg = gst(olf);
      const owed = round2(add(add(add(add(opening, fc), g), olf), olg));
      const due = minDue(md, { retailCashOutstandingPaise: paise(opening), financeChargesPaise: paise(fc), emiAmountPaise: 0,
        feesAndChargesPaise: paise(olf), gstAmountPaise: paise(round2(add(g, olg))), overlimitAmountPaise: paise(over), totalAmountDuePaise: paise(owed) });
      const pay = min(due, owed);
      const need = add(add(add(fc, g), olf), olg);
      if (cmp(pay, need) <= 0) {
        table.push({ cycle: n, openingPrincipalPaise: paise(opening), financeChargesPaise: paise(fc), gstPaise: paise(g), overlimitFeePaise: paise(olf),
          overLimitPaise: paise(over), minimumDuePaise: paise(due), paymentPaise: null, closingPrincipalPaise: null, infiniteDebtWarningRow: true });
        return { startingPrincipalPaise: startPaise, cyclesToPayoff: null, infiniteDebtWarning: true, totalInterestPaidPaise: null, totalGstPaidPaise: null,
          totalOverlimitFeesPaidPaise: null, totalExtraPaidPaise: null, amortizationTable: table,
          verdict: 'WARNING: minimum due does not cover interest. Balance grows indefinitely under minimum-only payments.' };
      }
      const L = lots.map((l) => ({ ...l }));
      L.push(lot(ce, 'finance_charges', round2(add(fc, g)), apr));
      if (isPos(add(olf, olg))) L.push(lot(ce, 'fees', round2(add(olf, olg)), apr));
      waterfall(L, pay, terms.order);
      const closing = round2(sumRemaining(L));
      table.push({ cycle: n, openingPrincipalPaise: paise(opening), financeChargesPaise: paise(fc), gstPaise: paise(g), overlimitFeePaise: paise(olf),
        overLimitPaise: paise(over), minimumDuePaise: paise(due), paymentPaise: paise(pay), closingPrincipalPaise: paise(closing), infiniteDebtWarningRow: false });
      lots = L.filter(open).map((l) => ({ ...l, day: ce }));
      cs += cycleLen;
      if (cmp(closing, ONE) < 0) break;
    }
    const s = (f) => table.reduce((a, r) => a + (r[f] ?? 0), 0);
    const ti = s('financeChargesPaise'), tg = s('gstPaise'), to = s('overlimitFeePaise'), extra = ti + tg + to;
    return { startingPrincipalPaise: startPaise, cyclesToPayoff: n, infiniteDebtWarning: false, totalInterestPaidPaise: ti, totalGstPaidPaise: tg,
      totalOverlimitFeesPaidPaise: to, totalExtraPaidPaise: extra, amortizationTable: table,
      verdict: `Paying only the minimum takes ${n} billing cycles and costs ₹${fmt(rupees(extra))} extra in interest + GST${to > 0 ? ' + overlimit fees' : ''}.` };
  }
  function noPayment(terms, tiers, olTerms, balPaise, limitPaise, cycleLen = 30) {
    const bal = rupees(balPaise), lf = rupees(lateFee(balPaise, tiers)), glf = gst(lf);
    let over = ZERO, once = ZERO;
    if (limitPaise != null) { over = rupees(utilization(balPaise, limitPaise).overLimitPaise); if (isPos(over) && olTerms) once = overlimitFeeD(over, olTerms); }
    const onceG = gst(once), apr = percent(terms.aprBps);
    const projections = [30, 60, 90].map((d) => {
      const i = accrue([], terms, 0, d - 1, [lot(0, 'retail_purchases', bal, apr)], false).totalInterest;
      const cyc = Math.floor((d + cycleLen - 1) / cycleLen);
      const olt = isPos(over) ? round2(mul(once, D(cyc))) : ZERO;
      const tot = round2(add(add(add(add(add(add(bal, i), gst(i)), lf), glf), olt), gst(olt)));
      return { daysOverdue: d, interestAccruedPaise: paise(i), overlimitFeesAccruedPaise: paise(round2(add(olt, gst(olt)))), totalOutstandingPaise: paise(tot), delinquencyStage: delinquency(d) };
    });
    return { statementBalancePaise: balPaise, lateFeePaise: paise(lf), gstOnLateFeePaise: paise(glf), overLimitPaise: paise(over),
      overlimitFeeImmediatePaise: paise(round2(add(once, onceG))), projections };
  }
  function exitPaths(terms, md, balPaise, cycleLen = 30, targets = [3, 6, 12]) {
    if (balPaise <= 0) return { feasible: true, balancePaise: 0, message: 'Balance is already zero — nothing to exit from.' };
    const bal = rupees(balPaise);
    const targeted = targets.map((n) => {
      const f = search(terms, bal, n, cycleLen);
      if (!f) return { targetCycles: n, feasible: false };
      const it = round2(add(f.ti, f.tg));
      return { targetCycles: n, feasible: true, requiredPaymentPerCyclePaise: paise(f.pay), cyclesToDebtFree: f.cleared,
        totalInterestAndGstPaise: paise(it), totalCostPaise: paise(round2(add(bal, it))) };
    });
    const mo = minimumOnly(terms, md, balPaise, cycleLen, 360, null, null);
    const cheapest = targeted.filter((t) => t.feasible).sort((a, b) => a.totalInterestAndGstPaise - b.totalInterestAndGstPaise)[0];
    return { feasible: true, balancePaise: balPaise, payInFullNow: { amountRequiredPaise: balPaise, totalCostPaise: balPaise }, targetedPaths: targeted,
      continueMinimum: mo.infiniteDebtWarning ? { feasible: false } : { feasible: true, cyclesToDebtFree: mo.cyclesToPayoff, totalExtraPaidPaise: mo.totalExtraPaidPaise, totalCostPaise: balPaise + mo.totalExtraPaidPaise },
      recommendedPath: cheapest ? `targeted_payoff_${cheapest.targetCycles}cycles` : 'pay_in_full_now' };
  }
  /* ---------------- RepaymentPathCalculator ---------------- */
  function compounded(principal, aprPct, days) {
    if (!isPos(principal) || days <= 0) return ZERO;
    const dpr = divMC(divMC(aprPct, HUNDRED, 20, HU), D(365), 20, HU);
    const f = powMC(add(ONE, dpr), days, 20, HU);
    return mulMC(principal, roundMC(sub(f, ONE), 20, HU), 20, HU);
  }
  function repaySim(order, budgetPaise) {
    const cards = order.map((c) => ({ apr: percent(c.aprBps), min: rupees(c.minimumDuePaise), rem: rupees(c.balancePaise) }));
    const budget = rupees(budgetPaise); let month = 0, total = ZERO;
    while (cards.some((c) => isPos(c.rem)) && month < 360) {
      month++; let left = budget;
      for (const c of cards) {
        if (!isPos(c.rem)) continue;
        const i = compounded(c.rem, c.apr, 30), g = round2(mulMC(i, GST_RATE, 20, HU));
        c.rem = round2(add(add(c.rem, i), g)); total = round2(add(add(total, i), g));
        const pay = min(min(c.min, left), c.rem);
        c.rem = round2(sub(c.rem, pay)); left = round2(sub(left, pay));
      }
      for (const c of cards) {
        if (!isPos(c.rem) || !isPos(left)) continue;
        const ex = min(left, c.rem); c.rem = round2(sub(c.rem, ex)); left = round2(sub(left, ex)); break;
      }
    }
    return { monthsToDebtFree: month, totalInterestPaidPaise: paise(total), paymentOrder: order.map((c) => c.name) };
  }
  function repaymentPath(cards, budgetPaise) {
    const totalMin = cards.reduce((a, c) => a + c.minimumDuePaise, 0);
    if (budgetPaise < totalMin) return { feasible: false, message: `Monthly budget ₹${fmt(rupees(budgetPaise))} is less than total minimum due ₹${fmt(rupees(totalMin))}. Consider increasing your payment budget.` };
    const av = [...cards].sort((a, b) => b.aprBps - a.aprBps), sb = [...cards].sort((a, b) => a.balancePaise - b.balancePaise);
    const A = { ...repaySim(av, budgetPaise), description: 'Pay highest APR card first — minimizes total interest paid' };
    const S = { ...repaySim(sb, budgetPaise), description: 'Pay smallest balance first — faster psychological wins' };
    const aw = A.totalInterestPaidPaise <= S.totalInterestPaidPaise, sav = Math.abs(A.totalInterestPaidPaise - S.totalInterestPaidPaise);
    return { feasible: true, recommendedStrategy: aw ? 'avalanche' : 'snowball', avalanche: A, snowball: S, interestSavingsWithBestStrategyPaise: sav,
      verdict: `${aw ? 'Avalanche' : 'Snowball'} method saves you ₹${fmt(rupees(sav))} in interest. ${aw ? 'Focus extra payments on your highest APR card.' : 'Clear your smallest balance card first for momentum.'}` };
  }
  /* ---------------- Custom fees / full / partial ---------------- */
  function customCycle(fees, bal, cycleNo) {
    let total = ZERO; const breakdown = [];
    for (const f of fees || []) {
      if (f.appliesTo === 'ONCE' && cycleNo !== 1) continue;
      const amt = f.amountType === 'FLAT' ? rupees(f.flatPaise) : round2(divScale(mul(bal, percent(f.percentBps)), HUNDRED, 10, HU));
      total = round2(add(total, amt)); breakdown.push({ id: f.id, name: f.name, amountPaise: paise(amt) });
    }
    return { total, gst: gst(total), breakdown };
  }
  const customFees = (fees, balPaise, cycleNo) => { const c = customCycle(fees, rupees(balPaise), cycleNo); return { totalPaise: paise(c.total), gstPaise: paise(c.gst), breakdown: c.breakdown }; };
  function fullPayment(balPaise, fees) {
    const bal = rupees(balPaise), c = customCycle(fees, bal, 1), tot = round2(add(add(bal, c.total), c.gst));
    return { paymentPaise: balPaise, customFeesPaise: paise(c.total), gstOnCustomFeesPaise: paise(c.gst), customFeesBreakdown: c.breakdown, totalCostPaise: paise(tot), gracePreservedNextCycle: true,
      verdict: isPos(c.total) ? `Balance itself costs nothing extra — but ₹${fmt(add(c.total, c.gst))} in configured custom fees still applies.` : 'Zero extra cost — grace period carries into next cycle.' };
  }
  function partialPayment(terms, txns, cs, ce, payPaise, graceThis, nextTxns, ns, ne) {
    const t = accrue(txns, terms, cs, ce, [], graceThis);
    const purch = txns.filter((x) => x.type === 'PURCHASE').reduce((a, x) => add(a, rupees(x.amountPaise)), ZERO);
    const sb = round2(add(purch, t.totalInterest)), pay = rupees(payPaise);
    if (cmp(pay, sb) >= 0) return { scenario: 'full_payment_instead', paymentPaise: payPaise, statementBalancePaise: paise(sb), interestChargedThisCyclePaise: 0, gstOnInterestThisCyclePaise: 0,
      carriedPrincipalIntoNextCyclePaise: 0, gracePreservedNextCycle: true, nextCycleInterestIfNoFurtherPaymentPaise: null, verdict: 'Payment covers the full statement — use the full-payment result instead.' };
    const carry = t.closingLots.map((l) => ({ ...l }));
    waterfall(carry, pay, terms.order);
    const carried = round2(sumRemaining(carry)); let next = null;
    if (ns != null && ne != null) {
      const apr = percent(terms.aprBps);
      const ol = carry.filter(open).map((l) => (l.bucket === 'cash_advances' ? l : { ...l, apr }));
      ol.push(lot(ce, 'finance_charges', t.totalInterest, apr));
      next = accrue(nextTxns || [], terms, ns, ne, ol, false).totalInterest;
    }
    return { scenario: 'partial_payment', paymentPaise: payPaise, statementBalancePaise: paise(sb), interestChargedThisCyclePaise: paise(t.totalInterest), gstOnInterestThisCyclePaise: paise(gst(t.totalInterest)),
      carriedPrincipalIntoNextCyclePaise: paise(carried), gracePreservedNextCycle: false, nextCycleInterestIfNoFurtherPaymentPaise: next == null ? null : paise(next),
      verdict: `Carrying ₹${fmt(carried)} forward costs ₹${fmt(t.totalInterest)} interest this cycle` + (next != null ? `, and because grace is lost, next cycle's NEW purchases start accruing interest from day 1 — projected next-cycle interest: ₹${fmt(next)}.` : '.') };
  }
  /** Same loop as flat(), returning each cycle's closing balance (verified equal to simulateFlatPayment's totals). */
  function flatTrace(terms, startPaise, payPaise, maxCycles, cycleLen = 30) {
    const apr = percent(terms.aprBps), payment = rupees(payPaise);
    let lots = [lot(0, 'retail_purchases', rupees(startPaise), apr)], cs = 0, ti = ZERO, tg = ZERO; const rows = [];
    for (let c = 1; c <= maxCycles; c++) {
      const opening = round2(sumRemaining(lots));
      if (!isPos(opening)) break;
      const ce = cs + cycleLen - 1;
      const fc = accrue([], terms, cs, ce, lots, false).totalInterest, g = gst(fc);
      ti = round2(add(ti, fc)); tg = round2(add(tg, g));
      const owed = round2(add(add(opening, fc), g)), pay = min(payment, owed);
      const L = lots.map((l) => ({ ...l })); L.push(lot(ce, 'finance_charges', round2(add(fc, g)), apr));
      waterfall(L, pay, terms.order);
      lots = L.filter(open).map((l) => ({ ...l, day: ce })); cs += cycleLen;
      const closing = round2(sumRemaining(lots));
      rows.push({ cycle: c, openingPaise: paise(opening), interestPaise: paise(fc), gstPaise: paise(g), paymentPaise: paise(pay), closingPaise: paise(closing) });
      if (cmp(closing, ONE) < 0) break;
    }
    return { rows, totalInterestPaise: paise(ti), totalGstPaise: paise(tg), cleared: rows.length && rows[rows.length - 1].closingPaise < 100 ? rows.length : null };
  }
  return { D, ZERO, ONE, add, sub, mul, divScale, divMC, mulMC, powMC, setScale, round2, rupees, paise, gst, fmt, cmp,
    emi, emiSchedule, addMonths, accrue, lot, percent, minDue, minDuePaise, utilization, overlimitFee, lateFee, delinquency,
    simulateFlatPayment, findMinimumFlatPayment, minimumOnly, noPayment, exitPaths, repaymentPath, customFees, fullPayment, partialPayment, flatTrace };
})();
if (typeof module !== 'undefined') module.exports = DexCalc;
