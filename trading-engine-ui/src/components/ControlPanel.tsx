'use client';

interface Props {
  isRunning:   boolean;
  isConnected: boolean;
  onRun:       () => void;
  onStop:      () => void;
}

export default function ControlPanel({ isRunning, isConnected, onRun, onStop }: Props) {
  return (
    <div className="bg-card border border-border rounded p-4">
      {/* Connection badge */}
      <div className="flex items-center justify-between mb-4">
        <span className="text-xs text-muted uppercase tracking-wider">Engine Control</span>
        <div className="flex items-center gap-1.5">
          <div className={`w-2 h-2 rounded-full transition-colors ${isConnected ? 'bg-green' : 'bg-border'}`} />
          <span className={`text-xs ${isConnected ? 'text-green' : 'text-muted'}`}>
            {isConnected ? 'WS Connected' : 'WS Offline'}
          </span>
        </div>
      </div>

      {/* Status badge */}
      <div className={`text-center py-1 rounded text-sm font-bold mb-4 ${
        isRunning ? 'bg-green/10 text-green border border-green/30' : 'bg-border/50 text-muted border border-border'
      }`}>
        {isRunning ? '▶ RUNNING' : '■ STOPPED'}
      </div>

      {/* Buttons */}
      <div className="grid grid-cols-2 gap-2">
        <button
          onClick={onRun}
          disabled={isRunning}
          className="py-2.5 rounded font-bold text-sm transition-all
            bg-yellow text-black hover:bg-yellow/90
            disabled:bg-border disabled:text-muted disabled:cursor-not-allowed"
        >
          ▶ RUN
        </button>
        <button
          onClick={onStop}
          disabled={!isRunning}
          className="py-2.5 rounded font-bold text-sm transition-all
            bg-red/20 text-red border border-red/40 hover:bg-red/30
            disabled:bg-border disabled:text-muted disabled:border-border disabled:cursor-not-allowed"
        >
          ■ STOP
        </button>
      </div>

      {/* Description */}
      <p className="text-xs text-muted mt-3 text-center">
        20 orders/sec · 5% duplicate rate
      </p>
    </div>
  );
}
