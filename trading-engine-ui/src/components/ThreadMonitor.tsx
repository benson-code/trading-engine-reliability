'use client';

import { EngineStats } from '../types/trading';

interface Props { stats: EngineStats | null; }

function ThreadBar({ name, count, total, color }: {
  name: string; count: number; total: number; color: string;
}) {
  const pct = total > 0 ? (count / total) * 100 : 50;
  return (
    <div className="mb-2">
      <div className="flex justify-between text-xs mb-1">
        <span className={`font-mono ${color}`}>{name}</span>
        <span className="text-muted">{count.toLocaleString()} orders</span>
      </div>
      <div className="h-1.5 bg-border rounded-full overflow-hidden">
        <div
          className={`h-full rounded-full transition-all duration-300 ${
            color === 'text-green' ? 'bg-green' : 'bg-red'
          }`}
          style={{ width: `${pct.toFixed(1)}%` }}
        />
      </div>
    </div>
  );
}

export default function ThreadMonitor({ stats }: Props) {
  const buy  = stats?.buy_count  ?? 0;
  const sell = stats?.sell_count ?? 0;
  const total = buy + sell;
  const diff  = Math.abs(buy - sell);

  return (
    <div className="bg-card border border-border rounded p-4">
      <div className="flex justify-between items-center mb-3">
        <span className="text-xs text-muted uppercase tracking-wider">Thread Monitor (LC-1115)</span>
        {/* Alternation health indicator */}
        <span className={`text-xs font-mono ${diff <= 1 ? 'text-green' : 'text-red'}`}>
          {diff <= 1 ? '✓ BALANCED' : `DRIFT ${diff}`}
        </span>
      </div>

      <ThreadBar name="BUY-THREAD"  count={buy}  total={total} color="text-green" />
      <ThreadBar name="SELL-THREAD" count={sell} total={total} color="text-red"   />

      <div className="mt-2 text-center text-xs text-muted">
        |BUY − SELL| = <span className={diff <= 1 ? 'text-green' : 'text-red'}>{diff}</span>
        {' '}(must be ≤ 1 for strict alternation)
      </div>
    </div>
  );
}
