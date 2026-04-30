import type { Metadata } from 'next';
import './globals.css';

export const metadata: Metadata = {
  title: 'BTCUSDT — Trading Engine Simulator',
  description: 'Binance-style QA Demo: real-time trading engine with WebSocket stream',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className="dark">
      <body className="bg-bg text-text font-mono antialiased">{children}</body>
    </html>
  );
}
