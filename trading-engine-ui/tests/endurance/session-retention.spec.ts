import { test, expect } from '@playwright/test';

/**
 * Endurance — the time axis that functional tests do not have.
 *
 * The 2026-07 incident ran the whole functional suite green while the backend accumulated
 * 11.36M unreclaimable Order objects. Functional tests execute in milliseconds against a
 * handful of rows; at that scale a bounded and an unbounded collection are
 * indistinguishable, because both answer every functional question correctly. The defect
 * was only ever visible as growth over time.
 *
 * The same defect class exists in this frontend. `useTradingEngine` keeps two collections
 * that only ever grow:
 *
 *   seenIds  — `setSeenIds(prev => new Set(prev).add(id))` on every ORDER_CREATED.
 *              Never pruned, and no component reads it. Worse than a plain leak: the copy
 *              is O(n), so per-message main-thread cost rises with session length.
 *   klineMap — `klineMap.current.set(bucket, candle)`, never deleted, while the `klines`
 *              array it feeds is capped at MAX_KLINES.
 *
 * This test asserts the property that separates bounded from unbounded:
 * **when order volume grows 4x, does retained memory grow with it?**
 *
 * See docs/incident-2026-07-14-gc-death-spiral/RCA-zh-TW.md
 */

/** Orders per batch. Four batches ≈ 40k messages ≈ 33 min of a real session. */
const BATCH = 10_000;
const BATCHES = 4;

/** The engine emits ~20 orders/sec (Main.java: intervalMs = 100, two alternating threads). */
const ORDER_INTERVAL_MS = 50;

/**
 * Ceiling on retained heap growth between the first and last batch.
 *
 * A bounded hook retains the same capped structures at both points — 100 orders, 500
 * klines — so 30k additional orders should add essentially nothing, and growth sits in GC
 * jitter. Measured against the unbounded hook, the same span adds ~2.1 MB (~71 bytes per
 * order retained forever). 1 MB sits between the two with roughly 2x margin on the
 * defect side and 2.6x on the bounded side.
 *
 * The bounded run still moves 401 KB. That is consistent with the klines window filling
 * toward its 500-bucket cap — 40k orders at 50 ms spans about 400 buckets — which is
 * growth toward a bound rather than retention past one.
 *
 * Heap is the assertion rather than elapsed time deliberately — see the note on TIMING
 * below.
 */
const MAX_HEAP_GROWTH_BYTES = 1024 * 1024;

/**
 * TIMING: measured, attached to the report, and deliberately **not** asserted on.
 *
 * The O(n) Set copy does show up as wall-clock growth, but at these volumes the fixed
 * per-message cost (JSON parse, React render, k-line bucketing) dominates it. Repeated
 * runs of the identical scenario produced first-to-last ratios of 1.64x and 2.12x — a
 * spread wider than the effect being measured, on a quiet box. A shared CI runner is
 * noisier still, so a threshold placed anywhere in that range would flip on scheduling
 * luck rather than on code changes.
 *
 * The number is worth recording — it is the mobile-visible symptom, a dashboard that
 * janks the longer it is left open — but a measurement this environment-sensitive belongs
 * in the report, not in a gate.
 */

declare global {
  interface Window {
    __ws?: { onmessage: ((ev: { data: string }) => void) | null };
    __feedBatch?: (count: number, startSeq: number, intervalMs: number) => Promise<number>;
  }
}

/**
 * Replaces WebSocket before any page script runs, so the hook connects to a stub we can
 * drive at arbitrary speed. This is what lets 33 minutes of session compress into
 * seconds — the same trick the Java endurance tests use when they feed 100k orders in a
 * loop instead of waiting for the engine to emit them.
 *
 * Only the application's socket is captured. Next.js opens its own WebSocket for hot
 * reload at /_next/webpack-hmr; capturing that one instead sends every synthetic order
 * into the HMR client, where it is discarded — the hook then accumulates nothing and the
 * suite reports a confident green while measuring an empty page.
 *
 * close() deliberately does not fire onclose: the hook would schedule a reconnect and add
 * timer noise to a measurement that is about message handling.
 */
const WS_STUB = `
class FakeWebSocket {
  constructor(url) {
    this.url = url;
    this.readyState = FakeWebSocket.OPEN;
    this.onopen = null; this.onclose = null; this.onerror = null; this.onmessage = null;
    if (String(url).indexOf('/_next/') === -1) window.__ws = this;
    setTimeout(() => { if (this.onopen) this.onopen(); }, 0);
  }
  send() {}
  close() { this.readyState = FakeWebSocket.CLOSED; }
}
FakeWebSocket.CONNECTING = 0;
FakeWebSocket.OPEN = 1;
FakeWebSocket.CLOSING = 2;
FakeWebSocket.CLOSED = 3;
window.WebSocket = FakeWebSocket;

window.__feedBatch = async function (count, startSeq, intervalMs) {
  const ws = window.__ws;
  if (!ws || !ws.onmessage) throw new Error('WebSocket stub is not wired to the hook');

  const t0 = performance.now();
  for (let i = 0; i < count; i++) {
    const n = startSeq + i;
    const side = n % 2 === 0 ? 'BUY' : 'SELL';
    ws.onmessage({
      data: JSON.stringify({
        type: 'ORDER_CREATED',
        data: {
          order_id:    'ORD-' + side + '-' + String(n).padStart(6, '0'),
          type:        side,
          symbol:      'BTCUSDT',
          amount:      '0.10000000',
          price:       95000 + (n % 100),
          status:      'PENDING',
          timestamp:   1700000000000 + n * intervalMs,
          thread_name: side + '-THREAD',
        },
      }),
    });
    // Yield periodically so React flushes the queued updaters instead of coalescing the
    // whole batch into one render. Every batch yields the same number of times, so this
    // adds a constant, not a slope.
    if (i % 250 === 249) await new Promise(function (r) { setTimeout(r, 0); });
  }
  await new Promise(function (r) { setTimeout(r, 0); });
  return performance.now() - t0;
};
`;

test.describe('Endurance — retained memory must not grow with orders seen', () => {
  test('a 4x longer session does not retain 4x the memory', async ({ page, context }, testInfo) => {
    await page.addInitScript(WS_STUB);
    await page.goto('/');
    await page.waitForFunction(() => Boolean(window.__ws?.onmessage));

    const cdp = await context.newCDPSession(page);

    /**
     * Heap still occupied once the collector has run — that residue is the retained set,
     * which is what a leak grows. Measuring before collection would mostly report
     * short-lived garbage and drown the signal.
     */
    const retainedHeapBytes = async (): Promise<number> => {
      await cdp.send('HeapProfiler.collectGarbage');
      const { usedSize } = await cdp.send('Runtime.getHeapUsage');
      return usedSize;
    };

    const timings: number[] = [];
    const heaps: number[] = [];

    for (let batch = 0; batch < BATCHES; batch++) {
      timings.push(
        await page.evaluate(
          ({ count, start, interval }) => window.__feedBatch!(count, start, interval),
          { count: BATCH, start: batch * BATCH, interval: ORDER_INTERVAL_MS },
        ),
      );
      heaps.push(await retainedHeapBytes());
    }

    const kb = (n: number) => `${Math.round(n / 1024).toLocaleString()} KB`;
    const growth = heaps[heaps.length - 1] - heaps[0];

    const table = heaps
      .map(
        (h, i) =>
          `  after ${String((i + 1) * BATCH).padStart(6)} orders   ` +
          `retained ${kb(h).padStart(10)}   batch took ${Math.round(timings[i])
            .toLocaleString()
            .padStart(6)} ms`,
      )
      .join('\n');

    await testInfo.attach('retention-profile.txt', {
      body:
        `${table}\n\n` +
        `retained growth : ${kb(growth)} across ${(BATCHES - 1) * BATCH} extra orders\n` +
        `per-order cost  : ${(growth / ((BATCHES - 1) * BATCH)).toFixed(1)} bytes retained\n` +
        `timing ratio    : ${(timings[timings.length - 1] / timings[0]).toFixed(2)}x ` +
        `(recorded, not asserted — see TIMING note in the spec)\n`,
      contentType: 'text/plain',
    });

    expect(
      growth,
      `Retained heap grew ${kb(growth)} while order volume grew ${BATCHES}x. The hook is\n` +
        `holding structures that never shed entries — the frontend form of the 2026-07\n` +
        `defect. On a phone this ends as a tab the browser reclaims under memory pressure.\n\n` +
        `${table}\n`,
    ).toBeLessThan(MAX_HEAP_GROWTH_BYTES);
  });
});
