'use client';

import { useTradingEngine } from '../hooks/useTradingEngine';
import TradingChart   from '../components/TradingChart';
import OrderBook      from '../components/OrderBook';
import StatsPanel     from '../components/StatsPanel';
import ControlPanel   from '../components/ControlPanel';
import ThreadMonitor  from '../components/ThreadMonitor';

export default function Home() {
  const {
    orders, stats, klines, lastCandle,
    isConnected, isRunning, isDuplicate,
    startEngine, stopEngine,
  } = useTradingEngine();

  const price     = stats?.last_price;
  const prevClose = klines.length > 1 ? klines[klines.length - 2].close : price;
  const priceUp   = price != null && prevClose != null && price >= prevClose;

  return (
    <div className="min-h-screen bg-bg font-mono">

      {/* ── Top Bar ──────────────────────────────────────────────────────── */}
      <header className="bg-card border-b border-border px-4 py-2 flex items-center gap-6">
        {/* Logo */}
        <div className="flex items-center gap-2">
          <div className="w-7 h-7 bg-yellow rounded flex items-center justify-center
                          text-black font-black text-sm">B</div>
          <span className="font-bold text-text">Binance</span>
        </div>

        {/* Pair info */}
        <div className="flex items-center gap-4 border-l border-border pl-4">
          <span className="font-bold text-base text-text">BTC/USDT</span>
          {price != null ? (
            <span className={`text-xl font-black transition-colors
              ${priceUp ? 'text-green' : 'text-red'}`}>
              {price.toLocaleString('en-US', { minimumFractionDigits: 2 })}
            </span>
          ) : (
            <span className="text-xl text-muted">—</span>
          )}
        </div>

        {/* 24h stats (simulated from session data) */}
        {stats && (
          <div className="hidden md:flex items-center gap-6 text-xs">
            <div>
              <span className="text-muted">Total Orders</span>
              <span className="ml-2 text-text font-bold">{stats.total_generated.toLocaleString()}</span>
            </div>
            <div>
              <span className="text-muted">Duplicates</span>
              <span className={`ml-2 font-bold ${stats.has_duplicates ? 'text-red' : 'text-muted'}`}>
                {stats.duplicate_count}
              </span>
            </div>
            <div>
              <span className="text-muted">Cache HR</span>
              <span className="ml-2 text-yellow font-bold">
                {(stats.cache_hit_rate * 100).toFixed(1)}%
              </span>
            </div>
          </div>
        )}

        {/* WS indicator (pushed right) */}
        <div className="ml-auto flex items-center gap-1.5">
          <div className={`w-2 h-2 rounded-full ${isConnected ? 'bg-green animate-pulse' : 'bg-border'}`} />
          <span className="text-xs text-muted">{isConnected ? 'Live' : 'Offline'}</span>
        </div>
      </header>

      {/* ── Main Grid ────────────────────────────────────────────────────── */}
      <div className="p-3 grid grid-cols-12 gap-3">

        {/* Chart (8/12) */}
        <div className="col-span-12 lg:col-span-8 bg-card border border-border rounded">
          <div className="px-4 py-2 border-b border-border flex items-center justify-between">
            <div className="flex items-center gap-3">
              <span className="text-sm font-semibold">BTCUSDT</span>
              <span className="text-xs text-muted">5s Candlestick</span>
            </div>
            <div className="flex gap-2 text-xs text-muted">
              <span className="text-green">LC-1115 BUY-THREAD</span>
              <span>/</span>
              <span className="text-red">SELL-THREAD</span>
            </div>
          </div>
          <div className="p-2">
            <TradingChart data={klines} lastCandle={lastCandle ?? undefined} />
          </div>
        </div>

        {/* Right panel (4/12) */}
        <div className="col-span-12 lg:col-span-4 flex flex-col gap-3">
          <ControlPanel
            isRunning={isRunning}
            isConnected={isConnected}
            onRun={startEngine}
            onStop={stopEngine}
          />
          <StatsPanel  stats={stats} />
          <ThreadMonitor stats={stats} />
        </div>

        {/* Order table (full width) */}
        <div className="col-span-12">
          <OrderBook orders={orders} isDuplicate={isDuplicate} />
        </div>
      </div>

      {/* ── Footer ────────────────────────────────────────────────────────── */}
      <footer className="border-t border-border px-4 py-2 flex justify-between text-xs text-muted">
        <span>Binance QA Demo — trading-engine-simulator + Next.js + WebSocket</span>
        <span>LC-217 HashMap · LC-146 LRU · LC-65 Validation · LC-1115 Threads</span>
      </footer>
    </div>
  );
}
