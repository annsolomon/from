// k6 smoke (MASTER_PLAN 10): 10 VUs for 30s, run by `verify.sh full` (full.k6-smoke).
// Built on the P03 base library (perf/k6/lib/base.js) and thresholds template (perf/k6/thresholds.js).
// Later phases add their endpoints to SCENARIO_PATHS (reads) or ship a scenario from perf/k6/scenario.template.js.
import { check } from 'k6';
import { CLASS, get } from './k6/lib/base.js';
import { SHAPES, thresholds } from './k6/thresholds.js';

const SCENARIO_PATHS = ['/actuator/health'];

export const options = {
  ...SHAPES.smoke,
  thresholds: thresholds('smoke', [CLASS.HEALTH]),
};

export default function () {
  for (const path of SCENARIO_PATHS) {
    const res = get(path, { cls: CLASS.HEALTH });
    // X-Request-Id echo on the gateway arrives with its filter chain in P06; add the check there.
    check(res, { 'status 200': (r) => r.status === 200 });
  }
}
