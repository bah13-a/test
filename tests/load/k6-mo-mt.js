// Équivalent k6 (rampe vers 50 req/s par opérateur, objectif CDC §12.1). Usage :
//   k6 run -e BASE=https://vas.example.tn -e SECRET=... -e API_KEY=... tests/load/k6-mo-mt.js
import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    mo: { executor: 'ramping-arrival-rate', startRate: 10, timeUnit: '1s', preAllocatedVUs: 100, maxVUs: 400,
          stages: [{ target: 50, duration: '1m' }, { target: 150, duration: '3m' }, { target: 200, duration: '5m' }, { target: 0, duration: '30s' }], exec: 'mo' },
    api: { executor: 'constant-arrival-rate', rate: 100, timeUnit: '1s', duration: '8m', preAllocatedVUs: 100, maxVUs: 300, exec: 'api' },
  },
  thresholds: { 'http_req_duration{scenario:mo}': ['p(95)<2000'], 'http_req_duration{scenario:api}': ['p(95)<500'], http_req_failed: ['rate<0.001'] },
};
const BASE = __ENV.BASE || 'http://localhost:8080';

export function mo() {
  const id = `${__VU}-${__ITER}-${Date.now()}`;
  const r = http.post(`${BASE}/callbacks/mo?secret=${__ENV.SECRET}&id=${id}&from=98${String(Math.floor(Math.random() * 900000) + 100000)}&to=${__ENV.SHORTCODE || '85500'}&content=VOTE%20A&origin-connector=smppc_tt`);
  check(r, { ack: (x) => x.body === 'ACK/Jasmin' });
}

export function api() {
  const r = http.post(`${BASE}/api/v1/messages`, JSON.stringify({ to: '98' + String(Math.floor(Math.random() * 900000) + 100000), text: 'test', operator: 'TT', sender: '85500' }),
    { headers: { 'X-API-Key': __ENV.API_KEY, 'Content-Type': 'application/json' } });
  check(r, { accepted: (x) => x.status === 202 });
}
