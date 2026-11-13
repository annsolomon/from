const C = require('../src/calc.js');
const ref = require(process.argv[2] || './ref.json');
let pass = 0, fail = 0; const fails = [];
const eq = (k, i, a, b) => { const ok = JSON.stringify(a) === JSON.stringify(b); if (ok) pass++; else { fail++; if (fails.length < 15) fails.push({ k, i, js: a, java: b }); } };
const T = (t) => ({ aprBps: t.aprBps, cashAdvanceAprBps: t.cashAdvanceAprBps, order: t.order });
for (const r of ref) {
  const i = r.in, o = r.out;
  if (r.k === 'emi') {
    const s = C.emiSchedule(i.p, i.r, i.n, i.gst, i.fee, '2026-12-05');
    const inst = i.n <= 12 ? s.installments.map((x) => [x.principalPaise, x.interestPaise, x.gstPaise, x.totalPaise, x.outstandingAfterPaise]) : null;
    eq('emi', i, { emi: s.emiPaise, ti: s.totalInterestPaise, tg: s.totalGstPaise, sumTotal: s.installments.reduce((a, x) => a + x.totalPaise, 0),
      sumPrin: s.installments.reduce((a, x) => a + x.principalPaise, 0), inst }, o);
  } else if (r.k === 'flatSearch') {
    const f = C.findMinimumFlatPayment(T(i.terms), i.b, i.t, 30);
    eq('flatSearch', i, f && { pay: f.paymentPaise, cleared: f.clearedByCycle, ti: f.totalInterestPaise, tg: f.totalGstPaise }, o);
  } else if (r.k === 'flatSim') {
    { const tr = C.flatTrace(T(i.terms), i.b, i.pay, i.max, 30); const f0 = C.simulateFlatPayment(T(i.terms), i.b, i.pay, i.max, 30);
      eq('trace', i, [tr.totalInterestPaise, tr.totalGstPaise, tr.cleared], [f0.totalInterestPaise, f0.totalGstPaise, f0.clearedByCycle]); }
    const f = C.simulateFlatPayment(T(i.terms), i.b, i.pay, i.max, 30);
    eq('flatSim', i, { cleared: f.clearedByCycle, ti: f.totalInterestPaise, tg: f.totalGstPaise, feasible: f.feasible }, o);
  } else if (r.k === 'minDue') {
    const x = i.x;
    eq('minDue', i, { v: C.paise(C.minDue(i.terms, { retailCashOutstandingPaise: x[0], financeChargesPaise: x[1], emiAmountPaise: x[2], feesAndChargesPaise: x[3], gstAmountPaise: x[4], overlimitAmountPaise: x[5], totalAmountDuePaise: x[6] })) }, o);
  } else if (r.k === 'minOnly') {
    const m = C.minimumOnly(T(i.terms), i.md, i.b, 30, 360, i.limit, i.of);
    eq('minOnly', i, { cycles: m.cyclesToPayoff, inf: m.infiniteDebtWarning, ti: m.totalInterestPaidPaise, tg: m.totalGstPaidPaise, to: m.totalOverlimitFeesPaidPaise,
      extra: m.totalExtraPaidPaise, rows: m.amortizationTable.length,
      first: m.amortizationTable.slice(0, 3).map((c) => [c.openingPrincipalPaise, c.financeChargesPaise, c.gstPaise, c.overlimitFeePaise, c.minimumDuePaise, c.paymentPaise, c.closingPrincipalPaise]) }, o);
  } else if (r.k === 'noPay') {
    const tiers = [{ minOutstandingPaise: 0, maxOutstandingPaise: 50000, lateFeePaise: 0 }, { minOutstandingPaise: 50001, maxOutstandingPaise: 500000, lateFeePaise: 50000 },
      { minOutstandingPaise: 500001, maxOutstandingPaise: 1000000, lateFeePaise: 75000 }, { minOutstandingPaise: 1000001, maxOutstandingPaise: 2500000, lateFeePaise: 95000 },
      { minOutstandingPaise: 2500001, maxOutstandingPaise: 5000000, lateFeePaise: 110000 }, { minOutstandingPaise: 5000001, maxOutstandingPaise: null, lateFeePaise: 130000 }];
    const n = C.noPayment({ aprBps: 4284, cashAdvanceAprBps: 4284, order: ['finance_charges', 'fees', 'retail_purchases'] }, tiers, { percentBps: 250, flatMinimumPaise: 50000 }, i.b, i.limit, 30);
    eq('noPay', i, { lateFee: n.lateFeePaise, gstLate: n.gstOnLateFeePaise, over: n.overLimitPaise, olImm: n.overlimitFeeImmediatePaise,
      proj: n.projections.map((p) => [p.daysOverdue, p.interestAccruedPaise, p.overlimitFeesAccruedPaise, p.totalOutstandingPaise, p.delinquencyStage.stage]) }, o);
  } else if (r.k === 'repay') {
    const cards = [{ id: 'a', name: 'Saffron', balancePaise: 8500000, aprBps: 4284, minimumDuePaise: 425000 }, { id: 'b', name: 'Indus', balancePaise: 2000000, aprBps: 3600, minimumDuePaise: 100000 }, { id: 'c', name: 'Deccan', balancePaise: 4500000, aprBps: 4800, minimumDuePaise: 225000 }];
    const p = C.repaymentPath(cards, i.budget);
    eq('repay', i, { rec: p.recommendedStrategy, av: [p.avalanche.monthsToDebtFree, p.avalanche.totalInterestPaidPaise], sb: [p.snowball.monthsToDebtFree, p.snowball.totalInterestPaidPaise] }, o);
  } else if (r.k === 'custom' || r.k === 'full') {
    const fees = [{ id: 'af', name: 'Annual', appliesTo: 'ONCE', amountType: 'FLAT', flatPaise: 49900 }, { id: 'svc', name: 'Service', appliesTo: 'EVERY_CYCLE', amountType: 'PERCENT', percentBps: 35 }];
    if (r.k === 'custom') { const c = C.customFees(fees, i.b, i.c); eq('custom', i, { t: c.totalPaise, g: c.gstPaise }, o); }
    else eq('full', i, { tot: C.fullPayment(i.b, fees).totalCostPaise }, o);
  } else if (r.k === 'partial') {
    const t = { aprBps: 4284, cashAdvanceAprBps: 4284, order: ['finance_charges', 'fees', 'retail_purchases'] };
    const p = C.partialPayment(t, [{ day: 2, type: 'PURCHASE', amountPaise: 1500000 }, { day: 16, type: 'PURCHASE', amountPaise: 500000 }], 0, 29, i.pay, i.g,
      [{ day: 35, type: 'PURCHASE', amountPaise: 800000 }], 30, 59);
    eq('partial', i, { sc: p.scenario, sb: p.statementBalancePaise, i: p.interestChargedThisCyclePaise, c: p.carriedPrincipalIntoNextCyclePaise, n: p.nextCycleInterestIfNoFurtherPaymentPaise }, o);
  } else if (r.k === 'ledger') {
    const t = { aprBps: 4284, cashAdvanceAprBps: 4284, order: ['finance_charges', 'fees', 'retail_purchases'] };
    const a = C.accrue([{ day: 2, type: 'PURCHASE', amountPaise: 1500000 }, { day: 16, type: 'PURCHASE', amountPaise: 500000 }], t, 0, 29,
      [C.lot(0, 'retail_purchases', C.rupees(4000000), C.percent(4284))], i.grace);
    eq('ledger', i, { ti: C.paise(a.totalInterest) }, o);
  }
}
console.log('PASS', pass, 'FAIL', fail); if (fails.length) console.log(JSON.stringify(fails, null, 1).slice(0, 4000));
