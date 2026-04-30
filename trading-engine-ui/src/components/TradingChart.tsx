'use client';

import { useEffect, useRef } from 'react';
import { KLine } from '../types/trading';

interface Props {
  data:        KLine[];
  lastCandle?: KLine;
}

export default function TradingChart({ data, lastCandle }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const chartRef  = useRef<any>(null);
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const seriesRef = useRef<any>(null);

  // Create chart once on mount
  useEffect(() => {
    if (!containerRef.current) return;

    // Dynamic import to avoid SSR issues
    import('lightweight-charts').then(({ createChart }) => {
      const chart = createChart(containerRef.current!, {
        layout: {
          background: { color: '#1E2026' },
          textColor:  '#848E9C',
        },
        grid: {
          vertLines: { color: '#2B2F36' },
          horzLines: { color: '#2B2F36' },
        },
        crosshair: { mode: 1 },
        rightPriceScale: { borderColor: '#333740' },
        timeScale: {
          borderColor:   '#333740',
          timeVisible:   true,
          secondsVisible: true,
        },
        width:  containerRef.current!.clientWidth,
        height: 340,
      });

      const series = chart.addCandlestickSeries({
        upColor:      '#02C076',
        downColor:    '#F6465D',
        borderVisible: false,
        wickUpColor:  '#02C076',
        wickDownColor:'#F6465D',
      });

      chartRef.current  = chart;
      seriesRef.current = series;

      const onResize = () => {
        if (containerRef.current) {
          chart.applyOptions({ width: containerRef.current.clientWidth });
        }
      };
      window.addEventListener('resize', onResize);

      return () => {
        window.removeEventListener('resize', onResize);
        chart.remove();
      };
    });
  }, []);

  // Sync historical data when klines array changes
  useEffect(() => {
    if (!seriesRef.current || data.length === 0) return;
    // lightweight-charts requires time to be strictly increasing
    const sorted = [...data].sort((a, b) => a.time - b.time);
    seriesRef.current.setData(sorted.map(({ time, open, high, low, close }) =>
      ({ time, open, high, low, close })
    ));
  }, [data]);

  // Stream individual candle updates in real-time
  useEffect(() => {
    if (!seriesRef.current || !lastCandle) return;
    seriesRef.current.update({
      time:  lastCandle.time,
      open:  lastCandle.open,
      high:  lastCandle.high,
      low:   lastCandle.low,
      close: lastCandle.close,
    });
  }, [lastCandle]);

  return (
    <div className="relative">
      <div ref={containerRef} className="w-full" />
      {data.length === 0 && (
        <div className="absolute inset-0 flex items-center justify-center text-muted text-sm">
          Press RUN to start generating orders
        </div>
      )}
    </div>
  );
}
