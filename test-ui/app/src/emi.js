/* DexEmi — "least-loss exit" planner + EMI situation simulator.
   PROPOSED logic (not in the repo yet): every number comes from the verified DexCalc ports of libs/portfolio-calc.
   Foreclosure fee, no-cost-EMI subvention, missed-instalment handling and grace-loss are modelled HERE — the repo has
   no engine for them (EMI plans can only be marked closed/foreclosed; nothing prices it). */
const DexEmi = ((C) => {
  const DEFAULT_TERMS = { aprBps: 4284, cashAdvanceAprBps: 4284, order: ['finance_charges', 'fees', 'retail_purchases'] };
  const DEFAULT_MD = { formulaType: 'PERCENT_OF_OUTSTANDING', percentBps: 500, flatFloorPaise: 20000 };
  const LATE_TIERS = [
    { minOutstandingPaise: 0, maxOutstandingPaise: 50000, lateFeePaise: 0 }, { minOutstandingPaise: 50001, maxOutstandingPaise: 500000, lateFeePaise: 50000 },
    { minOutstandingPaise: 500001, maxOutstandingPaise: 1000000, lateFeePaise: 75000 }, { minOutstandingPaise: 1000001, maxOutstandingPaise: 2500000, lateFeePaise: 95000 },
    { minOutstandingPaise: 2500001, maxOutstandingPaise: 5000000, lateFeePaise: 110000 }, { minOutstandingPaise: 5000001, maxOutstandingPaise: null, lateFeePaise: 130000 }];
  const DEFAULT_OFFERS = [
    { id: 'std', name: 'Card EMI (issuer)', rateBps: 1599, feeBps: 100, feeMinPaise: 19900, feeMaxPaise: 300000, forecloseBps: 300, subvention: false, tenures: [3, 6, 9, 12, 18, 24] },
    { id: 'low', name: 'Card EMI (promo)', rateBps: 1300, feeBps: 0, feeMinPaise: 49900, feeMaxPaise: 49900, forecloseBps: 300, subvention: false, tenures: [3, 6, 9, 12] },
    { id: 'nce', name: 'No-cost EMI (merchant pays interest)', rateBps: 1500, feeBps: 0, feeMinPaise: 19900, feeMaxPaise: 19900, forecloseBps: 300, subvention: true, tenures: [3, 6, 9] }];
  const MIN_EMI_PRINCIPAL = 100000; // repo rule: emi_plans.principal_paise >= Rs 1,000
  const feeFor = (o, p) => Math.min(o.feeMaxPaise, Math.max(o.feeMinPaise, Math.floor(p * o.feeBps / 10000)));
  const gstOf = (x) => C.paise(C.gst(C.rupees(x)));

  /** Interest (+GST) charged on new spend S made mid-cycle when grace is lost (ledger engine, 30-day cycle). */
  function graceLossPerCycle(terms, newSpendPaise) {
    if (!newSpendPaise) return 0;
    const tx = [{ day: 14, type: 'PURCHASE', amountPaise: newSpendPaise }];
    const lost = C.accrue(tx, terms, 0, 29, [], false).totalInterest;
    const kept = C.accrue(tx, terms, 0, 29, [], true).totalInterest;
    const i = C.paise(C.sub(lost, kept));
    return i + gstOf(i);
  }

  function emiCost(offer, principal, n) {
    const fee = feeFor(offer, principal);
    const s = C.emiSchedule(principal, offer.rateBps, n, 1800, fee, null);
    const subvention = offer.subvention ? s.totalInterestPaise : 0; // merchant discount upfront = the interest
    const maxInst = Math.max(...s.installments.map((x) => x.totalPaise));
    return { s, fee, subvention, maxInst, extra: s.totalInterestPaise + s.totalGstPaise + fee - subvention };
  }

  /**
   * @param x { balancePaise, cashNowPaise, monthlyCapacityPaise, newSpendPerCyclePaise, windfall: {month, amountPaise}|null,
   *            terms, md, offers }
   */
  function plan(x) {
    const terms = x.terms || DEFAULT_TERMS, md = x.md || DEFAULT_MD, offers = x.offers || DEFAULT_OFFERS;
    const B = x.balancePaise, cash = Math.min(x.cashNowPaise || 0, B), R = B - cash, M = x.monthlyCapacityPaise;
    const gl = graceLossPerCycle(terms, x.newSpendPerCyclePaise || 0);
    const out = [];
    const push = (o) => out.push({ graceLossPaise: 0, feesPaise: 0, interestPaise: 0, gstPaise: 0, ...o,
      totalExtraPaise: (o.interestPaise || 0) + (o.gstPaise || 0) + (o.feesPaise || 0) + (o.graceLossPaise || 0) - (o.discountPaise || 0) });

    // 1. pay in full
    push({ id: 'pay_full', label: 'Pay the full statement now', group: 'Pay', feasible: x.cashNowPaise >= B,
      why: x.cashNowPaise >= B ? 'Zero interest; grace period stays intact.' : `Needs ₹${rs(B)} now; you have ₹${rs(x.cashNowPaise || 0)}.`, months: 0, firstPaymentPaise: B });
    if (R <= 0) return rank(out);

    // 2. revolve at your monthly capacity (flat)
    const md0 = C.minDuePaise(md, { retailCashOutstandingPaise: R });
    const tr = C.flatTrace(terms, R, M, 360);
    push({ id: 'revolve_capacity', label: `Pay ₹${rs(cash)} now, revolve the rest at ₹${rs(M)}/cycle`, group: 'Revolve',
      feasible: M >= md0 && tr.cleared != null, why: M < md0 ? `₹${rs(M)} is below the minimum due ₹${rs(md0)}.` : tr.cleared == null ? 'Never clears in 30 years.' : `Clears in ${tr.cleared} cycles at ${terms.aprBps / 100}% APR; grace on new purchases is lost until then.`,
      months: tr.cleared, interestPaise: tr.totalInterestPaise, gstPaise: tr.totalGstPaise, graceLossPaise: gl * (tr.cleared || 0), firstPaymentPaise: cash + Math.min(M, R), trace: tr.rows });

    // 3. minimum only
    const mo = C.minimumOnly(terms, md, R, 30, 360);
    const moLast = mo.amortizationTable[mo.amortizationTable.length - 1];
    const moCleared = !mo.infiniteDebtWarning && moLast && moLast.closingPrincipalPaise < 100;
    push({ id: 'minimum_only', label: `Pay ₹${rs(cash)} now, then minimum due only`, group: 'Revolve', feasible: moCleared && md0 <= M,
      why: mo.infiniteDebtWarning ? 'Minimum due never covers interest — the balance grows forever.' : !moCleared ? `Still ₹${rs(moLast.closingPrincipalPaise)} owed after 360 cycles (30 years) — the repo engine reports this as "takes 360 cycles" (bug).` : md0 > M ? `First minimum due ₹${rs(md0)} is above your ₹${rs(M)}.` : `${mo.cyclesToPayoff} cycles of shrinking minimums.`,
      stillOwedPaise: moCleared ? 0 : moLast && moLast.closingPrincipalPaise,
      months: mo.cyclesToPayoff, interestPaise: mo.totalInterestPaidPaise || 0, gstPaise: mo.totalGstPaidPaise || 0, graceLossPaise: gl * (mo.cyclesToPayoff || 0), firstPaymentPaise: cash + md0 });

    // 4. targeted revolving payoffs (ExitPathCalculator)
    for (const n of [3, 6, 12, 24]) {
      const f = C.findMinimumFlatPayment(terms, R, n, 30);
      if (!f) continue;
      push({ id: `revolve_target_${n}`, label: `Revolve, cleared in ${n} cycles (₹${rs(f.paymentPaise)}/cycle)`, group: 'Revolve', feasible: f.paymentPaise <= M,
        why: f.paymentPaise <= M ? 'Binary-searched smallest flat payment (repo ExitPathCalculator).' : `Needs ₹${rs(f.paymentPaise)}/cycle, above your ₹${rs(M)}.`,
        months: f.clearedByCycle, interestPaise: f.totalInterestPaise, gstPaise: f.totalGstPaise, graceLossPaise: gl * f.clearedByCycle, firstPaymentPaise: cash + f.paymentPaise });
    }

    // 5. voluntary EMI conversion (+ optional foreclosure at a windfall)
    for (const o of offers) for (const n of o.tenures) {
      const ok = R >= MIN_EMI_PRINCIPAL;
      const c = ok ? emiCost(o, R, n) : null;
      push({ id: `emi_${o.id}_${n}`, label: `Convert ₹${rs(R)} to ${o.name}, ${n} months @ ${o.rateBps / 100}%`, group: 'EMI', offer: o.name,
        feasible: ok && c.maxInst <= M, why: !ok ? 'Below the ₹1,000 EMI minimum.' : c.maxInst > M ? `Largest instalment ₹${rs(c.maxInst)} is above your ₹${rs(M)}.` :
          `Fixed instalments; pay each statement in full and grace on new purchases is kept.${o.subvention ? ' Merchant discount cancels the interest; you still pay GST on it + the fee.' : ''}`,
        months: n, interestPaise: c ? c.s.totalInterestPaise : 0, gstPaise: c ? c.s.totalGstPaise : 0, feesPaise: c ? c.fee : 0, discountPaise: c ? c.subvention : 0,
        firstPaymentPaise: c ? cash + c.s.installments[0].totalPaise : null, schedule: c && c.s.installments });
      if (ok && x.windfall && x.windfall.month < n) {
        const k = x.windfall.month, inst = c.s.installments.slice(0, k), O = c.s.installments[k - 1].outstandingAfterPaise;
        const ff = Math.round(O * o.forecloseBps / 10000), ffg = gstOf(ff);
        const paidI = inst.reduce((a, i) => a + i.interestPaise, 0), paidG = inst.reduce((a, i) => a + i.gstPaise, 0);
        const disc = o.subvention ? c.subvention : 0; // subvention already credited upfront; issuers usually claw back on foreclosure — modelled as kept
        push({ id: `emi_${o.id}_${n}_fc${k}`, label: `${o.name} ${n}m, foreclose after ${k} instalments`, group: 'EMI + foreclose', offer: o.name,
          feasible: c.maxInst <= M && x.windfall.amountPaise >= O + ff + ffg,
          why: x.windfall.amountPaise < O + ff + ffg ? `Foreclosing needs ₹${rs(O + ff + ffg)} at month ${k}.` : `Outstanding ₹${rs(O)} + ${o.forecloseBps / 100}% fee ₹${rs(ff)} + GST.`,
          months: k, interestPaise: paidI, gstPaise: paidG + ffg, feesPaise: c.fee + ff, discountPaise: o.subvention ? Math.min(disc, paidI) : 0,
          foreclosure: { outstandingPaise: O, feePaise: ff, gstPaise: ffg, interestAvoidedPaise: c.s.totalInterestPaise - paidI } });
      }
    }
    // 6. revolve, then clear at the windfall
    if (x.windfall) {
      const k = x.windfall.month, t = C.flatTrace(terms, R, M, k), last = t.rows[t.rows.length - 1];
      const rem = last ? last.closingPaise : R;
      push({ id: `revolve_windfall_${k}`, label: `Revolve at ₹${rs(M)}, clear the rest with the month-${k} windfall`, group: 'Revolve', feasible: M >= md0 && x.windfall.amountPaise >= rem,
        why: `₹${rs(rem)} left at cycle ${t.rows.length}.`, months: t.rows.length, interestPaise: t.totalInterestPaise, gstPaise: t.totalGstPaise, graceLossPaise: gl * t.rows.length });
    }
    const r = rank(out);
    // what monthly capacity would open the cheapest options
    r.capacityNeeded = out.filter((s) => s.firstPaymentPaise != null && s.id !== 'pay_full' && !s.stillOwedPaise).map((s) => ({ id: s.id, label: s.label,
      needPaise: s.schedule ? Math.max(...s.schedule.map((i) => i.totalPaise)) : (s.firstPaymentPaise - cash), totalExtraPaise: s.totalExtraPaise }))
      .filter((s) => s.needPaise > M).sort((a, b) => a.needPaise - b.needPaise).slice(0, 4);
    return r;
  }
  function rank(list) {
    const f = list.filter((s) => s.feasible).sort((a, b) => a.totalExtraPaise - b.totalExtraPaise || (a.months ?? 0) - (b.months ?? 0));
    return { best: f[0] || null, feasible: f, infeasible: list.filter((s) => !s.feasible), all: list };
  }
  const rs = (p) => (p == null ? '—' : (p / 100).toLocaleString('en-IN', { minimumFractionDigits: 0, maximumFractionDigits: 2 }));

  /* ---------- situations an EMI can be in ---------- */
  function situations(o) {
    const terms = o.terms || DEFAULT_TERMS, P = o.principalPaise, offer = o.offer || DEFAULT_OFFERS[0], n = o.tenure || 12;
    const c = emiCost(offer, P, n), inst = c.s.installments;
    const res = {};
    // A. EMI vs revolving at the same monthly outflow
    const avg = Math.round(inst.reduce((a, i) => a + i.totalPaise, 0) / n);
    const rv = C.simulateFlatPayment(terms, P, avg, 360, 30);
    res.sameOutflow = { emiExtraPaise: c.extra, monthlyPaise: avg, revolveCycles: rv.clearedByCycle, revolveExtraPaise: rv.totalInterestPaise + rv.totalGstPaise };
    // B. tenure ladder
    res.tenures = offer.tenures.map((t) => { const k = emiCost(offer, P, t); return { tenure: t, emiPaise: k.s.emiPaise, firstInstPaise: k.s.installments[0].totalPaise, extraPaise: k.extra }; });
    // C. foreclosure break-even after every instalment
    res.foreclose = inst.slice(0, n - 1).map((i, idx) => {
      const k = idx + 1, O = i.outstandingAfterPaise, ff = Math.round(O * offer.forecloseBps / 10000), cost = ff + gstOf(ff);
      const future = inst.slice(k).reduce((a, j) => a + j.interestPaise + j.gstPaise, 0);
      return { afterInstalment: k, outstandingPaise: O, forecloseCostPaise: cost, futureInterestGstPaise: future, savingPaise: future - cost, worthIt: future > cost };
    });
    // D. minimum due while the EMI runs (FINANCE_CHARGE_CONDITIONAL includes the instalment)
    const mdT = { formulaType: 'FINANCE_CHARGE_CONDITIONAL', flatFloorPaise: 20000, retailCashPercentBps: 500, roundUpToNearestPaise: 1000 };
    const other = o.otherSpendPaise || 1500000;
    const i1 = inst[0];
    res.minDue = { withEmi: C.minDuePaise(mdT, { retailCashOutstandingPaise: other, emiAmountPaise: i1.principalPaise + i1.interestPaise, gstAmountPaise: i1.gstPaise, totalAmountDuePaise: other + i1.totalPaise }),
      withoutEmi: C.minDuePaise(mdT, { retailCashOutstandingPaise: other, totalAmountDuePaise: other }), instalmentPaise: i1.totalPaise, otherSpendPaise: other };
    // E. missed instalment (statement = other spend + instalment unpaid)
    const stmt = other + i1.totalPaise;
    res.missed = C.noPayment(terms, LATE_TIERS, { percentBps: 250, flatMinimumPaise: 50000 }, stmt, null, 30);
    res.missed.statementPaise = stmt;
    // F. missed 3 instalments -> issuer cancels the EMI: remaining principal returns to revolving at APR (typical T&C; modelled)
    const O3 = inst[Math.min(2, n - 1)].outstandingAfterPaise;
    res.cancelled = { returnedToRevolvingPaise: O3, minimumOnly: C.minimumOnly(terms, DEFAULT_MD, O3 + 3 * i1.totalPaise, 30, 360) };
    // G. credit limit blocked by the outstanding principal
    const limit = o.creditLimitPaise || 30000000;
    res.limit = inst.map((i) => ({ n: i.number, blockedPaise: i.outstandingAfterPaise + i.principalPaise, available: limit - i.outstandingAfterPaise - i.principalPaise, utilBps: C.utilization(i.outstandingAfterPaise + i.principalPaise, limit).utilizationBps }));
    // H. grace: EMI keeps it (statement paid in full), revolving loses it
    res.grace = { perCyclePaise: graceLossPerCycle(terms, o.newSpendPerCyclePaise || 2000000), newSpendPaise: o.newSpendPerCyclePaise || 2000000 };
    // I. partial payment instead of conversion this cycle
    res.partial = C.partialPayment(terms, [{ day: 2, type: 'PURCHASE', amountPaise: P }], 0, 29, Math.round(P / 3), false, [{ day: 40, type: 'PURCHASE', amountPaise: o.newSpendPerCyclePaise || 2000000 }], 30, 59);
    res.schedule = c.s; res.fee = c.fee; res.extra = c.extra; res.subvention = c.subvention;
    return res;
  }
  return { plan, situations, DEFAULT_OFFERS, DEFAULT_TERMS, DEFAULT_MD, LATE_TIERS, emiCost, graceLossPerCycle, rs };
})(typeof DexCalc !== 'undefined' ? DexCalc : require('./calc.js'));
if (typeof module !== 'undefined') module.exports = DexEmi;
