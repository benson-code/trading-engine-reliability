package com.binance.payment.db;

import com.binance.payment.util.DatabaseUtil;
import io.qameta.allure.*;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

@Epic("Payment System")
@Feature("Database Verification — ACID & Balance")
class BalanceVerificationTest {

    @BeforeAll
    static void setUpDatabase() throws SQLException {
        DatabaseUtil.initSchema();
    }

    @BeforeEach
    void cleanUp() throws SQLException {
        DatabaseUtil.cleanTables();
    }

    // ─── Happy Path ──────────────────────────────────────────────────────────

    @Test
    @Story("Balance Deduction")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Successful payment deducts the exact amount from user balance")
    void payment_success_deducts_balance_correctly() throws SQLException {
        // Arrange
        String userId = "USER_001";
        BigDecimal initialBalance = new BigDecimal("1000.00");
        BigDecimal paymentAmount  = new BigDecimal("100.00");
        DatabaseUtil.insertAccount(userId, initialBalance);

        // Act: simulate payment processing
        DatabaseUtil.insertPayment("PAY_001", "ORD_001", userId, paymentAmount, "IDEM_001");
        DatabaseUtil.deductBalance(userId, paymentAmount);

        // Assert
        BigDecimal finalBalance = DatabaseUtil.getBalance(userId);
        assertEquals(0, new BigDecimal("900.00").compareTo(finalBalance),
                "Balance should be 900.00 after 100.00 deduction");
    }

    // ─── ACID — Rollback ─────────────────────────────────────────────────────

    @Test
    @Story("ACID — Atomicity & Rollback")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Payment failure triggers DB rollback — balance unchanged (ACID Atomicity)")
    void payment_failure_rollback_preserves_balance() throws SQLException {
        // Arrange
        String userId = "USER_002";
        BigDecimal initialBalance = new BigDecimal("100.00");
        BigDecimal paymentAmount  = new BigDecimal("500.00"); // Exceeds balance → will fail
        DatabaseUtil.insertAccount(userId, initialBalance);

        Connection conn = DatabaseUtil.getConnection();
        conn.setAutoCommit(false);

        try {
            DatabaseUtil.deductBalance(userId, paymentAmount); // Expected to throw
            conn.commit();
            fail("Should have thrown due to insufficient balance");
        } catch (SQLException e) {
            // Rollback on failure — this is ACID Atomicity in action
            conn.rollback();
        } finally {
            conn.setAutoCommit(true);
        }

        // Assert: balance must be unchanged after rollback
        BigDecimal finalBalance = DatabaseUtil.getBalance(userId);
        assertEquals(0, initialBalance.compareTo(finalBalance),
                "Balance must remain unchanged after rollback");
    }

    // ─── Idempotency at DB Level ─────────────────────────────────────────────

    @Test
    @Story("Idempotency — DB Unique Constraint")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Duplicate idempotency_key violates UNIQUE constraint — prevents double deduction")
    void duplicate_idempotency_key_prevents_double_deduction() throws SQLException {
        // Arrange
        String userId        = "USER_003";
        String idemKey       = "IDEM_UNIQUE_001";
        BigDecimal balance   = new BigDecimal("1000.00");
        BigDecimal amount    = new BigDecimal("100.00");
        DatabaseUtil.insertAccount(userId, balance);

        // Act: first payment succeeds
        DatabaseUtil.insertPayment("PAY_003", "ORD_003", userId, amount, idemKey);
        DatabaseUtil.deductBalance(userId, amount);

        // Act: duplicate payment attempt with same idempotency_key
        assertThrows(SQLException.class, () ->
                DatabaseUtil.insertPayment("PAY_003_RETRY", "ORD_003", userId, amount, idemKey),
                "UNIQUE constraint on idempotency_key must reject duplicates");

        // Assert: balance only deducted once
        assertEquals(0, new BigDecimal("900.00").compareTo(DatabaseUtil.getBalance(userId)),
                "Balance should only be deducted once");
        assertEquals(1, DatabaseUtil.countPaymentsByIdempotencyKey(idemKey),
                "Only one payment record should exist for the idempotency key");
    }

    // ─── Boundary Case ───────────────────────────────────────────────────────

    @Test
    @Story("Boundary Case")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("Payment of exact remaining balance succeeds (balance reaches zero)")
    void payment_exact_balance_brings_account_to_zero() throws SQLException {
        // Arrange
        String userId      = "USER_004";
        BigDecimal balance = new BigDecimal("50.00");
        DatabaseUtil.insertAccount(userId, balance);

        // Act
        DatabaseUtil.insertPayment("PAY_004", "ORD_004", userId, balance, "IDEM_004");
        DatabaseUtil.deductBalance(userId, balance);

        // Assert
        assertEquals(BigDecimal.ZERO.setScale(8), DatabaseUtil.getBalance(userId).setScale(8),
                "Balance should be exactly 0 after full deduction");
    }
}
