package com.binance.trading.db;

import com.binance.trading.model.Order;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

public class DBOrderRepository {

    private static final String URL  =
        "jdbc:mysql://127.0.0.1:3306/binance_test_db" +
        "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";
    private static final String USER = "binance_user";
    // NOTE-01 fix: read password from env var, fall back to default for local dev
    private static final String PASS =
        System.getenv("DB_PASSWORD") != null ? System.getenv("DB_PASSWORD") : "BinanceTest2026";

    // BUG-04 fix: separate lock object so we can safely reassign conn on reconnect
    private final Object dbLock = new Object();
    private Connection conn;

    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DB-WRITER");
        t.setDaemon(true);
        return t;
    });

    public DBOrderRepository() throws SQLException {
        conn = DriverManager.getConnection(URL, USER, PASS);
        System.out.println("[DB] Connected to MySQL: binance_test_db");
    }

    /** Non-blocking: queues the INSERT and returns immediately. */
    public void saveAsync(Order order, boolean isDuplicate) {
        writer.submit(() -> {
            try {
                doSave(order, isDuplicate);
            } catch (Exception e) {
                System.err.println("[DB] Save failed for " + order.getOrderId() + ": " + e.getMessage());
            }
        });
    }

    private void doSave(Order order, boolean isDuplicate) throws SQLException {
        String sql = "INSERT INTO orders " +
            "(order_id, type, price, amount, status, thread_name, timestamp, is_duplicate) " +
            "VALUES (?,?,?,?,?,?,?,?)";
        synchronized (dbLock) {
            try (PreparedStatement ps = getConn().prepareStatement(sql)) {
                ps.setString(1, order.getOrderId());
                ps.setString(2, order.getType());
                ps.setBigDecimal(3, order.getPrice());
                ps.setBigDecimal(4, parseSafe(order.getAmount()));
                ps.setString(5, order.getStatus() != null ? order.getStatus().name() : "PENDING");
                ps.setString(6, order.getThreadName());
                ps.setLong(7, order.getTimestamp());
                ps.setBoolean(8, isDuplicate);
                ps.executeUpdate();
            }
        }
    }

    public List<Map<String, Object>> getRecentOrders(int limit) throws SQLException {
        String sql = "SELECT order_id, type, price, amount, status, thread_name, " +
                     "timestamp, is_duplicate FROM orders ORDER BY timestamp DESC LIMIT ?";
        List<Map<String, Object>> result = new ArrayList<>();
        synchronized (dbLock) {
            try (PreparedStatement ps = getConn().prepareStatement(sql)) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("order_id",     rs.getString("order_id"));
                        row.put("type",         rs.getString("type"));
                        row.put("price",        rs.getBigDecimal("price"));
                        row.put("amount",       rs.getBigDecimal("amount"));
                        row.put("status",       rs.getString("status"));
                        row.put("thread_name",  rs.getString("thread_name"));
                        row.put("timestamp",    rs.getLong("timestamp"));
                        row.put("is_duplicate", rs.getBoolean("is_duplicate"));
                        result.add(row);
                    }
                }
            }
        }
        return result;
    }

    public long getTotalCount() throws SQLException {
        synchronized (dbLock) {
            try (Statement st = getConn().createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM orders")) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    // BUG-03 fix: drain the writer queue before closing the connection
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS))
                System.err.println("[DB] Writer did not finish within 10s; some orders may be lost");
        } catch (InterruptedException ignored) {}
        synchronized (dbLock) {
            try { if (conn != null) conn.close(); } catch (Exception ignored) {}
        }
    }

    // BUG-04 fix: reconnect if the MySQL connection timed out (default wait_timeout = 8h)
    private Connection getConn() throws SQLException {
        if (conn == null || conn.isClosed() || !conn.isValid(2)) {
            try { if (conn != null) conn.close(); } catch (Exception ignored) {}
            conn = DriverManager.getConnection(URL, USER, PASS);
            System.out.println("[DB] Reconnected to MySQL");
        }
        return conn;
    }

    private static BigDecimal parseSafe(String s) {
        try { return new BigDecimal(s); }
        catch (Exception e) { return BigDecimal.ZERO; }
    }
}
