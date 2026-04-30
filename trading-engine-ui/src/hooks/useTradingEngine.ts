'use client';

import { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import { Order, EngineStats, KLine, WsMessage } from '../types/trading';

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8092';
const WS_URL  = process.env.NEXT_PUBLIC_WS_URL  ?? 'ws://localhost:8093';

const KLINE_BUCKET_SEC = 5; // 5-second candlesticks
const MAX_ORDERS       = 100;
const MAX_KLINES       = 500;

export function useTradingEngine() {
  const [orders,       setOrders]       = useState<Order[]>([]);
  const [stats,        setStats]        = useState<EngineStats | null>(null);
  const [klines,       setKlines]       = useState<KLine[]>([]);
  const [lastCandle,   setLastCandle]   = useState<KLine | null>(null);
  const [isConnected,  setIsConnected]  = useState(false);
  const [isRunning,    setIsRunning]    = useState(false);
  const [seenIds,      setSeenIds]      = useState<Set<string>>(new Set());

  const wsRef           = useRef<WebSocket | null>(null);
  const klineMap        = useRef<Map<number, KLine>>(new Map());
  const reconnectDelay  = useRef(1000);   // BUG-09: exponential backoff state
  const reconnectTimer  = useRef<ReturnType<typeof setTimeout> | null>(null);
  const connectWsRef    = useRef<() => void>(() => {});  // stable ref for recursive reconnect

  // ── K-line computation ───────────────────────────────────────────────────

  const addToKline = useCallback((order: Order) => {
    const bucketSec = Math.floor(order.timestamp / 1000 / KLINE_BUCKET_SEC) * KLINE_BUCKET_SEC;
    const price     = order.price;
    const vol       = parseFloat(order.amount);
    const existing  = klineMap.current.get(bucketSec);

    const candle: KLine = existing
      ? { time: bucketSec, open: existing.open,
          high: Math.max(existing.high, price), low: Math.min(existing.low, price),
          close: price, volume: existing.volume + vol }
      : { time: bucketSec, open: price, high: price, low: price, close: price, volume: vol };

    klineMap.current.set(bucketSec, candle);
    setLastCandle(candle);
    setKlines(prev => {
      const idx = prev.findIndex(k => k.time === bucketSec);
      if (idx >= 0) {
        const next = [...prev]; next[idx] = candle; return next;
      }
      return [...prev, candle].slice(-MAX_KLINES);
    });
  }, []);

  // ── WebSocket connection ─────────────────────────────────────────────────

  const connectWs = useCallback(() => {
    if (wsRef.current?.readyState === WebSocket.OPEN) return;
    const ws = new WebSocket(WS_URL);
    wsRef.current = ws;

    ws.onopen = () => {
      setIsConnected(true);
      reconnectDelay.current = 1000; // BUG-09: reset backoff on successful connect
    };

    // BUG-09 fix: auto-reconnect with exponential backoff (1s → 2s → 4s → ... → 30s)
    ws.onclose = () => {
      setIsConnected(false);
      wsRef.current = null;
      if (reconnectTimer.current) clearTimeout(reconnectTimer.current);
      reconnectTimer.current = setTimeout(() => {
        reconnectDelay.current = Math.min(reconnectDelay.current * 2, 30_000);
        connectWsRef.current();
      }, reconnectDelay.current);
    };

    ws.onerror = () => setIsConnected(false);

    ws.onmessage = (event: MessageEvent) => {
      // BUG-05 fix: guard against malformed JSON from server
      try {
        const msg = JSON.parse(event.data as string) as WsMessage;
        if (msg.type === 'ORDER_CREATED') {
          const order = msg.data as Order;
          setOrders(prev => [order, ...prev].slice(0, MAX_ORDERS));
          setSeenIds(prev => new Set(prev).add(order.order_id));
          addToKline(order);
        } else if (msg.type === 'STATS_UPDATE') {
          const s = msg.data as EngineStats;
          setStats(s);
          setIsRunning(s.status === 'RUNNING');
        }
      } catch (e) {
        console.error('[WS] Malformed message, skipping:', e);
      }
    };
  }, [addToKline]);

  const disconnectWs = useCallback(() => {
    if (reconnectTimer.current) clearTimeout(reconnectTimer.current); // stop auto-reconnect
    wsRef.current?.close();
    wsRef.current = null;
  }, []);

  // ── Engine start / stop via REST ─────────────────────────────────────────

  const startEngine = useCallback(async () => {
    await fetch(`${API_URL}/api/v1/engine/start`, { method: 'POST' });
    connectWs();
  }, [connectWs]);

  const stopEngine = useCallback(async () => {
    await fetch(`${API_URL}/api/v1/engine/stop`, { method: 'POST' });
    setIsRunning(false);
  }, []);

  // Keep connectWsRef pointing at the latest connectWs (avoids stale closure in reconnect timer)
  useEffect(() => { connectWsRef.current = connectWs; }, [connectWs]);

  // Connect WS on mount to receive stats even before engine starts
  useEffect(() => {
    connectWs();
    return () => {
      if (reconnectTimer.current) clearTimeout(reconnectTimer.current);
      wsRef.current?.close();
    };
  }, [connectWs]);

  // BUG-10 fix: pre-compute duplicate positions in O(n), then look up in O(1)
  // Key: "{orderId}-{index}" for every entry that is NOT the first (newest) occurrence
  const dupPositions = useMemo(() => {
    const firstSeen = new Map<string, number>();
    const result    = new Set<string>();
    orders.forEach((o, i) => {
      if (firstSeen.has(o.order_id)) {
        result.add(`${o.order_id}-${i}`);
      } else {
        firstSeen.set(o.order_id, i);
      }
    });
    return result;
  }, [orders]);

  const isDuplicate = useCallback((orderId: string, index: number): boolean => {
    return dupPositions.has(`${orderId}-${index}`);
  }, [dupPositions]);

  return {
    orders, stats, klines, lastCandle,
    isConnected, isRunning, seenIds,
    startEngine, stopEngine, isDuplicate,
  };
}
