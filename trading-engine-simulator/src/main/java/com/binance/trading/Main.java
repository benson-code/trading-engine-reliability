package com.binance.trading;

import com.binance.trading.api.TradingApiServer;
import com.binance.trading.db.DBOrderRepository;
import com.binance.trading.engine.DesiredState;
import com.binance.trading.engine.OrderBook;
import com.binance.trading.engine.OrderCache;
import com.binance.trading.engine.TradingEngine;
import com.binance.trading.ws.TradingWebSocketServer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Entry point for the standalone trading engine + API + WebSocket server.
 *
 * Ports:
 *   8092 → REST API  (orders, status, engine start/stop)
 *   8093 → WebSocket (real-time order stream for the Next.js frontend)
 *
 * Usage:
 *   mvn package -DskipTests
 *   java -jar target/trading-engine-simulator-1.0.0.jar
 *
 * Frontend then connects via:
 *   http://<mac-ip>:3000   (Next.js dev server)
 *   ws://<mac-ip>:8093     (WebSocket stream)
 *
 * NOTE: On a fresh install the engine does NOT auto-start; press RUN in the UI
 *       (POST /api/v1/engine/start). From then on the operator's last start/stop
 *       is persisted (ENGINE_STATE_FILE, set by the systemd unit) and restored
 *       at boot. That is the fix for incident #2: a restart used to bring the
 *       process back with the generator silently STOPPED. Without the variable
 *       (tests, k6) behaviour is unchanged: always STOPPED at start.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        int restPort = args.length > 0 ? Integer.parseInt(args[0]) : 8092;
        int wsPort   = args.length > 1 ? Integer.parseInt(args[1]) : 8093;
        int intervalMs = 100; // 20 orders/sec total (10 BUY + 10 SELL)

        // Build engine (not started yet — waits for RUN button)
        OrderBook    orderBook  = new OrderBook();
        OrderCache   orderCache = new OrderCache(1000);
        TradingEngine engine    = new TradingEngine(orderBook, orderCache, intervalMs, 0.05);

        // Connect to MySQL
        DBOrderRepository db = new DBOrderRepository();

        // Start WebSocket server
        TradingWebSocketServer wsServer = new TradingWebSocketServer(wsPort);
        wsServer.start();

        // Wire engine → WebSocket + MySQL: every new order is broadcast AND persisted
        engine.setOrderListener(order -> {
            wsServer.broadcast("ORDER_CREATED", order);
            boolean isDuplicate = orderBook.getOrderIdFrequency()
                                           .getOrDefault(order.getOrderId(), 0) > 1;
            db.saveAsync(order, isDuplicate);
        });

        // Start REST API server. Operator intent (start/stop) is persisted through it.
        DesiredState desired = DesiredState.fromEnv();
        TradingApiServer restServer = new TradingApiServer(restPort, engine, db, desired);
        restServer.start();

        // Incident #2 fix: restore what the operator last asked for. Only the
        // operator's intent is honoured - never "it was running when we died",
        // because the shutdown hook below stops the engine on every SIGTERM.
        boolean restored = desired.load().orElse(false);
        if (restored) engine.start();

        // Broadcast stats every second to all connected WS clients
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            if (wsServer.getClientCount() == 0) return;
            try {
                Map<String, Object> stats = new LinkedHashMap<>();
                stats.put("status",          engine.isRunning() ? "RUNNING" : "STOPPED");
                stats.put("total_generated", engine.getTotalGenerated());
                stats.put("unique_orders",   engine.getOrderBook().uniqueOrderCount());
                stats.put("total_orders",    engine.getOrderBook().totalOrderCount());
                stats.put("duplicate_count", engine.getOrderBook().findDuplicateOrderIds().size());
                stats.put("cache_size",      engine.getOrderCache().size());
                stats.put("cache_hit_count", engine.getOrderCache().getHitCount());
                stats.put("cache_miss_count",engine.getOrderCache().getMissCount());
                stats.put("cache_hit_rate",  engine.getOrderCache().getHitRate());
                stats.put("has_duplicates",  engine.getOrderBook().hasDuplicates());
                stats.put("buy_count",       engine.getBuyCount());
                stats.put("sell_count",      engine.getSellCount());
                stats.put("last_price",      engine.getLastPrice());
                wsServer.broadcast("STATS_UPDATE", stats);
            } catch (Exception ignored) {}
        }, 1, 1, TimeUnit.SECONDS);

        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║    Binance Trading Engine Simulator          ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.printf("  REST API  : http://0.0.0.0:%d/api/v1/status%n", restPort);
        System.out.printf("  WebSocket : ws://0.0.0.0:%d%n", wsPort);
        System.out.printf("  Engine    : %s%s%n",
                engine.isRunning() ? "RUNNING (restored from " + desired.path() + ")" : "STOPPED",
                engine.isRunning() ? "" : desired.isEnabled() ? " — no prior intent recorded; press RUN"
                                                                  : " — press RUN in the UI to start");
        System.out.println("  Press Ctrl+C to stop.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down...");
            // Close external entry points first, then engine, then DB
            scheduler.shutdown();
            try { scheduler.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            restServer.stop();
            try { wsServer.stop(1000); } catch (Exception ignored) {}
            engine.stop();
            db.close();
            System.out.printf("Done. Total orders generated: %d%n", engine.getTotalGenerated());
        }));

        Thread.currentThread().join();
    }
}
