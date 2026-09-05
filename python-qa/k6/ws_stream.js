// Concurrent-client load on the trading engine's WebSocket stream.
//
// This is the fan-out question pytest cannot answer: `broadcast()` iterates
// every open socket on a single scheduler thread, once per second. That loop is
// O(clients) and runs inline — so the cost of a slow or half-dead client is paid
// by every other client's latency. One pytest client cannot show that; N can.
//
//   ./k6/run.sh ws_stream.js
//   CLIENTS=50 DURATION=30s ./k6/run.sh ws_stream.js
//
// The target instance runs with its engine STOPPED (run.sh starts it that way),
// so no orders are generated and nothing is written to the shared MySQL table.
// Only STATS_UPDATE traffic is exercised — see README "Known gaps".

import { WebSocket } from 'k6/experimental/websockets';
import { setTimeout } from 'k6/timers';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const WS_URL = __ENV.WS_URL || 'ws://127.0.0.1:8093';
const CLIENTS = Number(__ENV.CLIENTS || 10);
const DURATION = __ENV.DURATION || '15s';
// How long each VU holds its socket open, in ms.
const HOLD_MS = Number(__ENV.HOLD_MS || 10000);

const messages = new Counter('ws_messages_received');
const statsMessages = new Counter('ws_stats_messages');
const connectErrors = new Counter('ws_connect_errors');
const gapSeconds = new Trend('ws_broadcast_gap_seconds');
const wellFormed = new Rate('ws_message_well_formed');

export const options = {
  scenarios: {
    subscribers: {
      executor: 'per-vu-iterations',
      vus: CLIENTS,
      iterations: 1,
      maxDuration: DURATION,
    },
  },
  thresholds: {
    ws_connect_errors: ['count==0'],
    ws_message_well_formed: ['rate==1.00'],
    // The broadcast is scheduled at 1s. If fan-out to N clients starts costing
    // real time, the gap seen by a client stretches — this is the signal.
    ws_broadcast_gap_seconds: ['p(95)<2', 'max<5'],
    // Each client should see roughly one message per second it stayed connected.
    ws_stats_messages: [`count>${Math.floor(CLIENTS * (HOLD_MS / 1000) * 0.6)}`],
  },
};

export default function () {
  const socket = new WebSocket(WS_URL);
  let last = null;

  socket.onerror = (e) => {
    connectErrors.add(1);
    console.error(`ws error: ${e && e.error ? e.error : e}`);
  };

  socket.onopen = () => {
    // Hold the connection, then close cleanly — an abrupt teardown would test
    // k6's exit path rather than the server's onClose handling.
    setTimeout(() => socket.close(), HOLD_MS);
  };

  socket.onmessage = (e) => {
    messages.add(1);

    let parsed = null;
    try {
      parsed = JSON.parse(e.data);
    } catch (_) {
      wellFormed.add(false);
      return;
    }

    const ok = check(parsed, {
      'envelope has type': (m) => typeof m.type === 'string',
      'envelope has data': (m) => m.data !== undefined && m.data !== null,
    });
    wellFormed.add(ok);

    if (parsed.type === 'STATS_UPDATE') {
      statsMessages.add(1);
      // The engine must stay stopped — if this instance ever starts generating,
      // it writes into the shared orders table. Fail loudly rather than pollute.
      check(parsed.data, {
        'engine stayed STOPPED': (d) => d.status === 'STOPPED',
        'no orders generated': (d) => d.total_generated === 0,
      });

      const now = Date.now();
      if (last !== null) gapSeconds.add((now - last) / 1000);
      last = now;
    }
  };
}

export function handleSummary(data) {
  const m = data.metrics;
  const val = (name, stat = 'count') =>
    m[name] && m[name].values[stat] !== undefined
      ? m[name].values[stat].toFixed(2) : 'n/a';

  return {
    stdout: [
      '',
      `WebSocket fan-out — ${CLIENTS} clients holding ${HOLD_MS / 1000}s`,
      `  messages received       ${val('ws_messages_received')}`,
      `  STATS_UPDATE            ${val('ws_stats_messages')}`,
      `  connect errors          ${val('ws_connect_errors')}`,
      `  broadcast gap p95 (s)   ${val('ws_broadcast_gap_seconds', 'p(95)')}`,
      `  broadcast gap max (s)   ${val('ws_broadcast_gap_seconds', 'max')}`,
      '',
    ].join('\n'),
  };
}
