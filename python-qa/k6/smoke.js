// Functional gate for the Payment API — run this before any load test.
//
// One VU, a handful of iterations: it answers "is the contract intact?", not
// "how fast is it?". If this fails, a load run would only produce noise.
//
//   k6 run -e BASE_URL=http://127.0.0.1:8091 -e API_KEY=... k6/smoke.js

import http from 'k6/http';
import { check, fail } from 'k6';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8091';
const API_KEY = __ENV.API_KEY || '';
// Stamped into every idempotency key so re-running the script never replays a
// previous run's payments.
const RUN_ID = __ENV.RUN_ID || `${Date.now()}`;

export const options = {
  vus: 1,
  iterations: 20,
  thresholds: {
    // A smoke test has no tolerance budget: one failure means broken, not slow.
    http_req_failed: ['rate==0'],
    checks: ['rate==1.00'],
    // Loose on purpose — this is a hang detector. Latency is load_payments.js's job.
    http_req_duration: ['p(95)<1000'],
  },
};

function headers() {
  const h = { 'Content-Type': 'application/json' };
  if (API_KEY) h['X-API-Key'] = API_KEY;
  return h;
}

export function setup() {
  const r = http.get(`${BASE_URL}/api/v1/health`);
  if (r.status !== 200) {
    fail(`target not healthy before smoke: HTTP ${r.status} ${r.body}`);
  }
}

export default function () {
  const n = exec.scenario.iterationInTest;
  const key = `k6-smoke-${RUN_ID}-${n}`;

  // 1. Create — a new key must be accepted, not replayed.
  const payload = JSON.stringify({
    order_id: `ORD-K6-${RUN_ID}-${n}`,
    user_id: `USER_K6_${RUN_ID}`,
    amount: 10.5,
    currency: 'USDT',
    idempotency_key: key,
  });

  const created = http.post(`${BASE_URL}/api/v1/payments`, payload,
    { headers: headers(), tags: { name: 'create' } });

  const createdOk = check(created, {
    'create -> 202': (r) => r.status === 202,
    'create returns payment_id': (r) => !!r.json('payment_id'),
    'create returns job_id': (r) => !!r.json('job_id'),
    'create status is PENDING': (r) => r.json('status') === 'PENDING',
  });
  if (!createdOk) return;   // nothing downstream is meaningful without an id

  // 2. Replay the same key — must dedupe to the same payment, answered 200.
  const replayed = http.post(`${BASE_URL}/api/v1/payments`, payload,
    { headers: headers(), tags: { name: 'replay' } });

  check(replayed, {
    'replay -> 200': (r) => r.status === 200,
    'replay returns the same payment_id': (r) =>
      r.json('payment_id') === created.json('payment_id'),
  });

  // 3. Poll the job — settlement is async, so PENDING here is legal too.
  const jobId = created.json('job_id');
  const status = http.get(`${BASE_URL}/api/v1/payments/${jobId}/status`,
    { headers: headers(), tags: { name: 'status' } });

  check(status, {
    'status -> 200': (r) => r.status === 200,
    'status is PENDING or SUCCESS': (r) =>
      ['PENDING', 'SUCCESS'].includes(r.json('status')),
  });
}
