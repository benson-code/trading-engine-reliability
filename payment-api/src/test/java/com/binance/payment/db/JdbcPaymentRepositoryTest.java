package com.binance.payment.db;

import com.binance.payment.model.PaymentRequest;
import com.binance.payment.model.PaymentResponse;
import com.binance.payment.service.JdbcPaymentRepository;
import io.qameta.allure.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P3 — DB-layer tests for {@link JdbcPaymentRepository}, the strict
 * JDBC-backed repository. These restore the real payment-domain negative
 * paths that {@link com.binance.payment.service.InMemoryPaymentRepository}
 * (auto-provisioning) cannot express, and prove the ACID rollback is real.
 */
@Epic("Payment System")
@Feature("JDBC Repository — Strict Accounts & ACID")
class JdbcPaymentRepositoryTest {

    private JdbcPaymentRepository repo;

    @BeforeEach
    void freshDb() {
        // A unique in-mem DB name per test → full isolation (the default
        // 'paymentdb' name would be shared across instances).
        repo = new JdbcPaymentRepository(
                "jdbc:h2:mem:t_" + UUID.randomUUID().toString().replace("-", "")
                        + ";DB_CLOSE_DELAY=-1;MODE=MySQL",
                "sa", "");
    }

    @AfterEach
    void closeDb() {
        if (repo != null) repo.close();
    }

    private PaymentRequest req(String order, String user, String amount, String key) {
        return PaymentRequest.builder()
                .orderId(order).userId(user)
                .amount(new BigDecimal(amount)).currency("USDT")
                .idempotencyKey(key).build();
    }

    // ─── Happy Path ──────────────────────────────────────────────────────────

    @Test
    @Story("Balance Deduction")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("createPayment debits the funded account and persists the payment")
    void happy_path_debits_and_persists() {
        repo.seedAccount("U1", new BigDecimal("1000.00"));

        PaymentResponse resp = repo.createPayment(req("O1", "U1", "150.00", "K1"));

        assertNotNull(resp.getPaymentId());
        assertEquals(0, new BigDecimal("850.00").compareTo(repo.getBalance("U1")),
                "balance must be 1000 - 150");
        assertTrue(repo.findByIdempotencyKey("K1").isPresent(),
                "payment row must be persisted");
    }

    // ─── Negative: account does not exist ────────────────────────────────────

    @Test
    @Story("Negative — Unknown Account")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Unknown account → NoSuchElementException, nothing persisted")
    void unknown_account_is_rejected() {
        assertThrows(NoSuchElementException.class,
                () -> repo.createPayment(req("O2", "GHOST", "10.00", "K2")));

        assertTrue(repo.findByIdempotencyKey("K2").isEmpty(),
                "no payment row may exist for a rejected unknown account");
    }

    // ─── Negative: insufficient balance → ACID rollback ──────────────────────

    @Test
    @Story("ACID — Atomicity & Rollback")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Insufficient balance → IllegalStateException; debit rolled back, no payment row")
    void insufficient_balance_rolls_back_atomically() {
        repo.seedAccount("U3", new BigDecimal("100.00"));

        assertThrows(IllegalStateException.class,
                () -> repo.createPayment(req("O3", "U3", "500.00", "K3")));

        assertEquals(0, new BigDecimal("100.00").compareTo(repo.getBalance("U3")),
                "balance must be UNCHANGED after rollback");
        assertTrue(repo.findByIdempotencyKey("K3").isEmpty(),
                "no payment row may exist after rollback (atomicity)");
    }

    // ─── Idempotency backstop: duplicate key never double-debits ─────────────

    @Test
    @Story("Idempotency — UNIQUE constraint backstop")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Duplicate idempotency_key debits exactly once (rollback undoes the 2nd debit)")
    void duplicate_key_debits_exactly_once() {
        repo.seedAccount("U4", new BigDecimal("1000.00"));

        PaymentResponse first  = repo.createPayment(req("O4", "U4", "200.00", "K4"));
        // Direct second call with the same key — exercises the repo's own
        // UNIQUE-constraint + rollback backstop (PaymentService would normally
        // short-circuit before reaching here).
        PaymentResponse second = repo.createPayment(req("O4", "U4", "200.00", "K4"));

        assertEquals(first.getPaymentId(), second.getPaymentId(),
                "duplicate key must yield the same payment_id");
        assertEquals(0, new BigDecimal("800.00").compareTo(repo.getBalance("U4")),
                "balance must reflect exactly ONE 200 debit despite two calls");
    }
}
