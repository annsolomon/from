// k6 smoke (MASTER_PLAN 10): 10 VUs for 30s. P00 target: gateway /actuator/health.
// Later phases add their endpoints to SCENARIO_PATHS.
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const SCENARIO_PATHS = ['/actuator/health'];

export const options = {
  vus: 10,
  duration: '30s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<200'],
  },
};

export default function () {
  for (const path of SCENARIO_PATHS) {
    const res = http.get(`${BASE}${path}`, { headers: { 'X-Request-Id': `k6-${__VU}-${__ITER}` } });
    check(res, { 'status 200': (r) => r.status === 200 });
  }
}
