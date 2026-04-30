package com.binance.payment.util;

import java.math.BigDecimal;
import java.sql.*;

/**
 * H2 in-memory DB helper — simulates the payment service's PostgreSQL schema.
 * Used by BalanceVerificationTest and PaymentFlowTest.
 */
public class DatabaseUtil {

    private static final String JDBC_URL = "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=MySQL";
    private static final String USER = "sa";
    private static final String PASSWORD = "";

    private static Connection connection;

    public static Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection(JDBC_URL, USER, PASSWORD);
        }
        return connection;
    }

    public static void initSchema() throws SQLException {
        try (Statement stmt = getConnection().createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS accounts (
                    user_id    VARCHAR(50)    PRIMARY KEY,
                    balance    DECIMAL(18,8)  NOT NULL,
                    currency   VARCHAR(10)    NOT NULL DEFAULT 'USDT'
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS payments (
                    payment_id      VARCHAR(50)   PRIMARY KEY,
                    order_id        VARCHAR(50)   NOT NULL,
                    user_id         VARCHAR(50)   NOT NULL,
                    amount          DECIMAL(18,8) NOT NULL,
                    status          VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
                    idempotency_key VARCHAR(100)  UNIQUE NOT NULL,
                    created_at      TIMESTAMP     DEFAULT CURRENT_TIMESTAMP
                )
            """);
        }
    }

    public static void cleanTables() throws SQLException {
        try (Statement stmt = getConnection().createStatement()) {
            stmt.execute("DELETE FROM payments");
            stmt.execute("DELETE FROM accounts");
        }
    }

    public static void insertAccount(String userId, BigDecimal balance) throws SQLException {
        try (PreparedStatement ps = getConnection().prepareStatement(
                "INSERT INTO accounts (user_id, balance) VALUES (?, ?)")) {
            ps.setString(1, userId);
            ps.setBigDecimal(2, balance);
            ps.executeUpdate();
        }
    }

    public static BigDecimal getBalance(String userId) throws SQLException {
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT balance FROM accounts WHERE user_id = ?")) {
            ps.setString(1, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getBigDecimal("balance");
            throw new RuntimeException("User not found: " + userId);
        }
    }

    /**
     * Simulates the atomic deduction step inside a payment transaction.
     * Throws SQLException if balance is insufficient (triggers rollback in tests).
     */
    public static void deductBalance(String userId, BigDecimal amount) throws SQLException {
        try (PreparedStatement ps = getConnection().prepareStatement(
                "UPDATE accounts SET balance = balance - ? WHERE user_id = ? AND balance >= ?")) {
            ps.setBigDecimal(1, amount);
            ps.setString(2, userId);
            ps.setBigDecimal(3, amount);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                throw new SQLException("Insufficient balance or user not found for userId=" + userId);
            }
        }
    }

    public static void insertPayment(String paymentId, String orderId, String userId,
                                     BigDecimal amount, String idempotencyKey) throws SQLException {
        try (PreparedStatement ps = getConnection().prepareStatement(
                "INSERT INTO payments (payment_id, order_id, user_id, amount, status, idempotency_key) " +
                "VALUES (?, ?, ?, ?, 'SUCCESS', ?)")) {
            ps.setString(1, paymentId);
            ps.setString(2, orderId);
            ps.setString(3, userId);
            ps.setBigDecimal(4, amount);
            ps.setString(5, idempotencyKey);
            ps.executeUpdate();
        }
    }

    public static int countPaymentsByIdempotencyKey(String key) throws SQLException {
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT COUNT(*) FROM payments WHERE idempotency_key = ?")) {
            ps.setString(1, key);
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getInt(1);
        }
    }
}
