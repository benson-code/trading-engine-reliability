// Steady-state load on POST /payments.
//
// Uses constant-arrival-rate, not ramping VUs, on purpose. Arrival rate models
// what clients actually do (send N requests per second) and, unlike a VU ramp,
// it cannot silently escalate when the server slows down — `maxVUs` is a hard
// ceiling on the load this script can put on the machine. That matters when the
// box is shared with something else you must not disturb.
//
//   k6 run -e BASE_URL=... -e API_KEY=... k6/load_payments.js
//   k6 run -e RATE=50 -e DURATION=2m ... k6/load_payments.js
//
// Defaults are deliberately gentle (20 rps / 30s). Raise RATE only on a box
// where you own all the CPU.

import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8091';
const API_KEY = __ENV.API_KEY || '';
const RUN_ID = __ENV.RUN_ID || `${Date.now()}`;
const RATE = Number(__ENV.RATE || 20);
const DURATION = __ENV.DURATION || '30s';

// Business-level outcomes, tracked separately from HTTP status. A 402 is a
// perfectly good HTTP response and a failed payment — http_req_failed cannot
// tell them apart, so these exist to make that distinction visible.
const acceptedRate = new Rate('payment_accepted');
const rejectedRate = new Rate('payment_rejected_business');
const createLatency = new Trend('payment_create_duration', true);

export const options = {
  scenarios: {
    steady: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(5, Math.ceil(RATE / 4)),
      maxVUs: Math.max(10, Math.ceil(RATE / 2)),   // hard ceiling on concurrency
    },
  },
  thresholds: {
    // p95 under 300ms, and the tail must not collapse either — a good p95 with
    // a 5s p99 is a queue forming, which is exactly what a payment API must not do.
    payment_create_duration: ['p(95)<300', 'p(99)<1000'],
    http_req_failed: ['rate<0.01'],
    payment_accepted: ['rate>0.99'],
    checks: ['rate>0.99'],
  },
};

function headers() {
  const h = { 'Content-Type': 'application/json' };
  if (API_KEY) h['X-API-Key'] = API_KEY;
  return h;
}

export default function () {
  // Unique per (VU, iteration) so every request is a genuinely new payment —
  // a shared key would turn this into a dedupe benchmark by accident.
  const key = `k6-load-${RUN_ID}-${exec.vu.idInTest}-${exec.scenario.iterationInTest}`;

  const res = http.post(`${BASE_URL}/api/v1/payments`, JSON.stringify({
    order_id: `ORD-${key}`,
    // Spread across accounts: one shared account would serialise on its balance
    // row and measure lock contention rather than API throughput.
    user_id: `USER_K6_${RUN_ID}_${exec.vu.idInTest}`,
    amount: 1.25,
    currency: 'USDT',
    idempotency_key: key,
  }), { headers: headers(), tags: { name: 'create' } });

  createLatency.add(res.timings.duration);
  acceptedRate.add(res.status === 202);
  // 402/422 are the server correctly refusing — distinct from a 5xx or a timeout.
  rejectedRate.add(res.status === 402 || res.status === 422);

  check(res, {
    'accepted (202)': (r) => r.status === 202,
    'no server error': (r) => r.status < 500,
    'body carries payment_id': (r) => !!r.json('payment_id'),
  });
}

export function handleSummary(data) {
  const m = data.metrics;
  const line = (label, v) => `  ${label.padEnd(28)} ${v}`;
  const get = (name, stat) =>
    m[name] && m[name].values[stat] !== undefined
      ? m[name].values[stat].toFixed(2) : 'n/a';

  return {
    stdout: [
      '',
      `Payment API load — ${RATE} rps for ${DURATION}`,
      line('requests', m.http_reqs ? m.http_reqs.values.count : 'n/a'),
      line('accepted rate', get('payment_accepted', 'rate')),
      line('create p95 (ms)', get('payment_create_duration', 'p(95)')),
      line('create p99 (ms)', get('payment_create_duration', 'p(99)')),
      line('create max (ms)', get('payment_create_duration', 'max')),
      line('http failures', get('http_req_failed', 'rate')),
      '',
    ].join('\n'),
  };
}
