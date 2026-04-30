# Binance Trading Engine UI

Real-time BTC/USDT trading dashboard for the [trading-engine-simulator](https://github.com/benson-code/trading-engine-simulator) backend.

Built with **Next.js 15**, **TypeScript**, **Tailwind CSS**, and **lightweight-charts** (TradingView).

---

## Features

| Component | Description |
|-----------|-------------|
| **TradingChart** | Live 5-second OHLCV candlestick chart — candles built in-browser from WebSocket order stream |
| **OrderBook** | Newest-first order table; duplicate orders highlighted in yellow ⚠ |
| **ThreadMonitor** | BUY-THREAD vs SELL-THREAD progress bars; shows `\|BUY − SELL\| ≤ 1` health indicator (LC-1115) |
| **StatsPanel** | Cache hit rate, total/unique/duplicate order counts, last price |
| **ControlPanel** | RUN / STOP buttons — calls `POST /api/v1/engine/start` and `stop` |

---

## Tech Stack

- **Next.js 15** (App Router, `'use client'`)
- **TypeScript**
- **Tailwind CSS** — Binance dark theme (`#0B0E11` background, `#02C076` green, `#F6465D` red)
- **lightweight-charts 4.2** — TradingView candlestick library (dynamic import for SSR)
- **WebSocket** — native browser API for order streaming

---

## Architecture

```
Java Engine (8093 WS)
       │
       │  ORDER_CREATED  { order_id, type, price, amount, thread_name, timestamp }
       │  STATS_UPDATE   { buy_count, sell_count, cache_hit_rate, ... }
       ▼
useTradingEngine.ts  (custom hook)
  ├── addToKline()     → 5s OHLCV buckets → setKlines / setLastCandle
  ├── setOrders()      → last 100 orders newest-first
  ├── dupPositions     → useMemo O(n) pre-compute of duplicate positions
  └── auto-reconnect   → exponential backoff (1s → 2s → 4s → … → 30s)
       │
  ┌────┴─────────────────────────┐
  │  TradingChart (lightweight-  │
  │  charts candlestick)         │
  │  OrderBook (dup highlighted) │
  │  ThreadMonitor (bars)        │
  │  StatsPanel                  │
  └──────────────────────────────┘
```

---

## Prerequisites

- Node.js 18+
- Running [trading-engine-simulator](https://github.com/benson-code/trading-engine-simulator) backend

---

## Setup

```bash
npm install
```

Create `.env.local`:
```env
NEXT_PUBLIC_API_URL=http://<backend-ip>:8092
NEXT_PUBLIC_WS_URL=ws://<backend-ip>:8093
```

Replace `<backend-ip>` with `localhost` for local dev, or your server's IP for remote access.

---

## Run

```bash
npm run dev      # development (http://localhost:3000)
npm run build    # production build
npm start        # production server
```

---

## Usage

1. Open `http://localhost:3000`
2. Click **RUN** — the engine starts generating BUY/SELL orders
3. Watch the candlestick chart build in real-time
4. Duplicate orders appear highlighted in yellow in the order table
5. ThreadMonitor shows `✓ BALANCED` when `|BUY − SELL| ≤ 1`
6. Click **STOP** to pause order generation (MySQL data is preserved)

---

## Key Implementation Details

### WebSocket Reconnect (BUG-09 fix)
Auto-reconnects with exponential backoff on disconnect:
```
1s → 2s → 4s → 8s → … → 30s (max)
Resets to 1s on successful reconnect
```

### Candlestick Building
Orders are grouped into 5-second OHLCV buckets client-side:
```
bucket = Math.floor(timestamp / 1000 / 5) * 5
```
Each new order updates the current bucket's `high`, `low`, `close`, and `volume`.

### Duplicate Detection (O(n) — BUG-10 fix)
Pre-computes a `Set<"orderId-index">` via `useMemo` each render cycle.
Lookup per row is O(1) instead of the previous O(n) `findIndex` scan.

---

## Backend

See [trading-engine-simulator](https://github.com/benson-code/trading-engine-simulator) for the full Java backend with REST API documentation, test results, and MySQL schema.

---

## License

MIT
