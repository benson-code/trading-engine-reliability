'use client';

import { Order } from '../types/trading';

interface Props {
  orders:        Order[];
  isDuplicate:   (orderId: string, index: number) => boolean;
}

function formatTime(ts: number): string {
  return new Date(ts).toLocaleTimeString('en-US', { hour12: false });
}

export default function OrderBook({ orders, isDuplicate }: Props) {
  return (
    <div className="bg-card border border-border rounded">
      {/* Header */}
      <div className="px-4 py-2 border-b border-border flex items-center justify-between">
        <span className="text-sm font-semibold">
          Live Orders
          <span className="ml-2 text-xs text-muted font-normal">
            (last {orders.length}, newest first)
          </span>
        </span>
        <div className="flex gap-4 text-xs text-muted">
          <span className="text-green">■ BUY</span>
          <span className="text-red">■ SELL</span>
          <span className="text-yellow">⚠ DUPLICATE</span>
        </div>
      </div>

      {/* Table */}
      <div className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead>
            <tr className="border-b border-border text-muted">
              <th className="px-3 py-2 text-left font-normal w-20">Time</th>
              <th className="px-3 py-2 text-left font-normal">Order ID</th>
              <th className="px-3 py-2 text-left font-normal w-14">Type</th>
              <th className="px-3 py-2 text-right font-normal w-24">Price (USDT)</th>
              <th className="px-3 py-2 text-right font-normal w-24">Amount (BTC)</th>
              <th className="px-3 py-2 text-left font-normal w-24">Thread</th>
              <th className="px-3 py-2 text-center font-normal w-20">Status</th>
              <th className="px-3 py-2 text-center font-normal w-16">Dup?</th>
            </tr>
          </thead>
          <tbody>
            {orders.length === 0 ? (
              <tr>
                <td colSpan={8} className="px-3 py-8 text-center text-muted">
                  No orders yet — press RUN to start
                </td>
              </tr>
            ) : (
              orders.map((order, i) => {
                const dup     = isDuplicate(order.order_id, i);
                const isBuy   = order.type === 'BUY';
                return (
                  <tr
                    key={`${order.order_id}-${order.timestamp}-${i}`}
                    className={`border-b border-border/30 transition-colors
                      ${dup ? 'bg-yellow/5' : 'hover:bg-border/20'}`}
                  >
                    <td className="px-3 py-1.5 text-muted">{formatTime(order.timestamp)}</td>
                    <td className={`px-3 py-1.5 font-mono ${dup ? 'text-yellow' : 'text-text'}`}>
                      {order.order_id}
                    </td>
                    <td className={`px-3 py-1.5 font-bold ${isBuy ? 'text-green' : 'text-red'}`}>
                      {order.type}
                    </td>
                    <td className="px-3 py-1.5 text-right text-text">
                      {order.price.toLocaleString('en-US', { minimumFractionDigits: 2 })}
                    </td>
                    <td className="px-3 py-1.5 text-right text-muted">{order.amount}</td>
                    <td className={`px-3 py-1.5 text-muted text-xs ${isBuy ? 'text-green/70' : 'text-red/70'}`}>
                      {order.thread_name}
                    </td>
                    <td className="px-3 py-1.5 text-center">
                      <span className="px-1.5 py-0.5 rounded text-xs bg-border/50 text-muted">
                        {order.status}
                      </span>
                    </td>
                    <td className="px-3 py-1.5 text-center">
                      {dup && <span className="text-yellow font-bold">⚠</span>}
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
