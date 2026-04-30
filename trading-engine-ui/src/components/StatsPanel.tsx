'use client';

import { EngineStats } from '../types/trading';

interface Props { stats: EngineStats | null; }

function Row({ label, value, color }: { label: string; value: string; color?: string }) {
  return (
    <div className="flex justify-between items-center py-1.5 border-b border-border/50 last:border-0">
      <span className="text-xs text-muted">{label}</span>
      <span className={`text-xs font-mono font-bold ${color ?? 'text-text'}`}>{value}</span>
    </div>
  );
}

export default function StatsPanel({ stats }: Props) {
  if (!stats) {
    return (
      <div className="bg-card border border-border rounded p-4">
        <span className="text-xs text-muted uppercase tracking-wider">Statistics</span>
        <div className="mt-3 text-center text-muted text-xs py-4">Waiting for engine...</div>
      </div>
    );
  }

  const hitRate   = (stats.cache_hit_rate * 100).toFixed(1);
  const dupRate   = stats.total_orders > 0
    ? ((stats.duplicate_count / stats.total_orders) * 100).toFixed(1)
    : '0.0';

  return (
    <div className="bg-card border border-border rounded p-4">
      <span className="text-xs text-muted uppercase tracking-wider">Statistics</span>

      <div className="mt-3 space-y-0">
        {/* Order counters */}
        <Row label="Total Generated" value={stats.total_generated.toLocaleString()} />
        <Row label="Unique Orders"   value={stats.unique_orders.toLocaleString()} color="text-green" />
        <Row label="Duplicates"      value={`${stats.duplicate_count} (${dupRate}%)`}
             color={stats.has_duplicates ? 'text-red' : 'text-muted'} />

        {/* LRU cache */}
        <div className="pt-1.5">
          <span className="text-xs text-muted/70">── LRU Cache (LC-146) ──</span>
        </div>
        <Row label="Cache Size"  value={`${stats.cache_size} / 1000`} />
        <Row label="Hit Rate"    value={`${hitRate}%`}
             color={parseFloat(hitRate) > 60 ? 'text-green' : 'text-yellow'} />
        <Row label="Hits / Misses"
             value={`${stats.cache_hit_count} / ${stats.cache_miss_count}`} />
      </div>
    </div>
  );
}
