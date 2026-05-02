package com.binance.trading.api;

import com.binance.trading.db.DBOrderRepository;
import com.binance.trading.engine.TradingEngine;
import com.binance.trading.model.Order;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Embedded JDK HTTP server exposing the trading engine via REST.
 *
 * Endpoints:
 *   GET  /api/v1/status              → engine metrics (also used by frontend stats panel)
 *   GET  /api/v1/orders              → all orders + metadata
 *   GET  /api/v1/orders/{id}         → single order (hits LRU cache first)
 *   POST /api/v1/orders              → inject order manually
 *   GET  /api/v1/orders/duplicates   → duplicate order analysis
 *   GET  /api/v1/orders/history      → persistent orders from MySQL (requires DB)
 *   POST /api/v1/engine/start        → start order generation (called by frontend RUN button)
 *   POST /api/v1/engine/stop         → stop order generation  (called by frontend STOP button)
 *
 * All responses include CORS headers for the Next.js frontend (port 3000).
 */
public class TradingApiServer {

    private final HttpServer       server;
    private final TradingEngine    engine;
    private final DBOrderRepository db;
    private final ObjectMapper     mapper = new ObjectMapper();
    private final int              port;

    public TradingApiServer(int port, TradingEngine engine) throws IOException {
        this(port, engine, null);
    }

    public TradingApiServer(int port, TradingEngine engine, DBOrderRepository db) throws IOException {
        this.port   = port;
        this.engine = engine;
        this.db     = db;
        this.server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);

        server.createContext("/api/v1/engine",            this::handleEngine);
        server.createContext("/api/v1/orders/duplicates", this::handleDuplicates);
        server.createContext("/api/v1/orders/history",    this::handleHistory);
        server.createContext("/api/v1/orders",            this::handleOrders);
        server.createContext("/api/v1/status",            this::handleStatus);
        server.setExecutor(Executors.newFixedThreadPool(8));
    }

    public void start() { server.start(); }
    public void stop()  { server.stop(0); }
    public int  getPort(){ return port; }

    // ── POST /api/v1/engine/start|stop ────────────────────────────────────────

    private void handleEngine(HttpExchange ex) throws IOException {
        cors(ex);
        if (preflight(ex)) return;

        String path = ex.getRequestURI().getPath();
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, toJson(Map.of("error", "Method Not Allowed")));
            return;
        }
        if (path.endsWith("/start")) {
            engine.start();
            send(ex, 200, toJson(Map.of("status", "RUNNING", "message", "Engine started")));
        } else if (path.endsWith("/stop")) {
            engine.stop();
            send(ex, 200, toJson(Map.of("status", "STOPPED", "message", "Engine stopped")));
        } else {
            send(ex, 404, toJson(Map.of("error", "Unknown engine action")));
        }
    }

    // ── GET/POST /api/v1/orders[/{id}] ────────────────────────────────────────

    private void handleOrders(HttpExchange ex) throws IOException {
        cors(ex);
        if (preflight(ex)) return;

        String method = ex.getRequestMethod();
        String path   = ex.getRequestURI().getPath();

        if ("GET".equals(method)) {
            String[] parts = path.split("/");
            if (parts.length > 4 && !parts[4].isEmpty()) {
                String orderId = parts[4];
                Order order    = engine.getOrderCache().get(orderId);
                if (order == null) order = engine.getOrderBook().getOrder(orderId);
                if (order == null) {
                    send(ex, 404, toJson(Map.of("error", "Order not found", "order_id", orderId)));
                } else {
                    send(ex, 200, toJson(order));
                }
            } else {
                // BUG-06 fix: add ?limit= param (default 500, max 5000) to prevent huge responses
                int limit = parseLimit(ex.getRequestURI().getQuery(), 500, 5000);
                List<Order> page = engine.getOrderBook().getAllOrders()
                    .stream().limit(limit).collect(java.util.stream.Collectors.toList());
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("total_count",     engine.getOrderBook().totalOrderCount());
                resp.put("unique_count",    engine.getOrderBook().uniqueOrderCount());
                resp.put("duplicate_count", engine.getOrderBook().findDuplicateOrderIds().size());
                resp.put("cache_size",      engine.getOrderCache().size());
                resp.put("returned",        page.size());
                resp.put("orders",          page);
                send(ex, 200, toJson(resp));
            }
        } else if ("POST".equals(method)) {
            // BUG-07 fix: cap body at 64 KB to prevent OOM via large payload
            byte[] body  = ex.getRequestBody().readNBytes(65_536);
            Order  order = mapper.readValue(body, Order.class);
            boolean isNew = engine.getOrderBook().addOrder(order);
            engine.getOrderCache().put(order.getOrderId(), order);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("order_id", order.getOrderId());
            resp.put("is_new",   isNew);
            resp.put("message",  isNew ? "Order created" : "Duplicate order detected — not reprocessed");
            send(ex, isNew ? 201 : 200, toJson(resp));
        } else {
            send(ex, 405, toJson(Map.of("error", "Method Not Allowed")));
        }
    }

    // ── GET /api/v1/orders/history?limit=100 ─────────────────────────────────

    private void handleHistory(HttpExchange ex) throws IOException {
        cors(ex);
        if (preflight(ex)) return;
        if (!"GET".equals(ex.getRequestMethod())) {
            send(ex, 405, toJson(Map.of("error", "Method Not Allowed")));
            return;
        }
        if (db == null) {
            send(ex, 503, toJson(Map.of("error", "DB not configured")));
            return;
        }
        try {
            String query = ex.getRequestURI().getQuery();
            int limit = 100;
            if (query != null && query.contains("limit=")) {
                try { limit = Integer.parseInt(query.replaceAll(".*limit=(\\d+).*", "$1")); }
                catch (NumberFormatException ignored) {}
            }
            limit = Math.min(Math.max(limit, 1), 1000);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("source",      "MySQL binance_test_db.orders");
            resp.put("total_in_db", db.getTotalCount());
            resp.put("returned",    limit);
            resp.put("orders",      db.getRecentOrders(limit));
            send(ex, 200, toJson(resp));
        } catch (Exception e) {
            send(ex, 500, toJson(Map.of("error", "DB error: " + e.getMessage())));
        }
    }

    // ── GET /api/v1/orders/duplicates ─────────────────────────────────────────

    private void handleDuplicates(HttpExchange ex) throws IOException {
        cors(ex);
        if (preflight(ex)) return;
        if (!"GET".equals(ex.getRequestMethod())) {
            send(ex, 405, toJson(Map.of("error", "Method Not Allowed")));
            return;
        }
        List<String> dupIds = engine.getOrderBook().findDuplicateOrderIds();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("has_duplicates",      !dupIds.isEmpty());
        resp.put("duplicate_count",     dupIds.size());
        resp.put("duplicate_order_ids", dupIds);
        resp.put("frequency_map",       engine.getOrderBook().getOrderIdFrequency());
        send(ex, 200, toJson(resp));
    }

    // ── GET /api/v1/status ────────────────────────────────────────────────────

    private void handleStatus(HttpExchange ex) throws IOException {
        cors(ex);
        if (preflight(ex)) return;
        if (!"GET".equals(ex.getRequestMethod())) {
            send(ex, 405, toJson(Map.of("error", "Method Not Allowed")));
            return;
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status",          engine.isRunning() ? "RUNNING" : "STOPPED");
        resp.put("total_generated", engine.getTotalGenerated());
        resp.put("unique_orders",   engine.getOrderBook().uniqueOrderCount());
        resp.put("total_orders",    engine.getOrderBook().totalOrderCount());
        resp.put("duplicate_count", engine.getOrderBook().findDuplicateOrderIds().size());
        resp.put("cache_size",      engine.getOrderCache().size());
        resp.put("cache_hit_count", engine.getOrderCache().getHitCount());
        resp.put("cache_miss_count",engine.getOrderCache().getMissCount());
        resp.put("cache_hit_rate",  engine.getOrderCache().getHitRate());
        resp.put("has_duplicates",  engine.getOrderBook().hasDuplicates());
        resp.put("buy_count",       engine.getBuyCount());
        resp.put("sell_count",      engine.getSellCount());
        resp.put("last_price",      engine.getLastPrice());
        send(ex, 200, toJson(resp));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void cors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin",  "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private boolean preflight(HttpExchange ex) throws IOException {
        if ("OPTIONS".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    private void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private String toJson(Object obj) {
        try { return mapper.writeValueAsString(obj); }
        catch (Exception e) { return "{\"error\":\"Serialization failed\"}"; }
    }

    private int parseLimit(String query, int defaultVal, int maxVal) {
        if (query == null) return defaultVal;
        try {
            for (String param : query.split("&")) {
                if (param.startsWith("limit="))
                    return Math.min(Math.max(Integer.parseInt(param.substring(6)), 1), maxVal);
            }
        } catch (NumberFormatException ignored) {}
        return defaultVal;
    }
}
