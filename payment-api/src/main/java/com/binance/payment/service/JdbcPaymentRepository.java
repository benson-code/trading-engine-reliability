package com.binance.payment.service;

import com.binance.payment.model.PaymentRequest;
import com.binance.payment.model.PaymentResponse;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JDBC-backed {@link PaymentRepository} (P3) — driver-agnostic ({@code java.sql}
 * only), defaulting to H2 in-memory in MySQL-compatibility mode so it runs with
 * no external database, yet exercises real SQL, real transactions and real
 * constraint enforcement.
 *
 * <p>Unlike {@link InMemoryPaymentRepository}, this implementation has <b>strict
 * account semantics</b>: an account that was never funded does not implicitly
 * exist. That restores the real payment-domain negative paths:</p>
 * <ul>
 *   <li>unknown account → {@link NoSuchElementException} (API → 404)</li>
 *   <li>insufficient balance → {@link IllegalStateException} (API → 402)</li>
 * </ul>
 *
 * <p><b>ACID core:</b> {@code createPayment} runs the balance debit and the
 * payment insert in a <i>single transaction</i>. The {@code UNIQUE} constraint
 * on {@code idempotency_key} is the concurrency backstop: if two requests with
 * the same new key race, both debit, one commits, the loser hits the constraint
 * and is <b>rolled back — which undoes its debit</b> — then returns the winning
 * payment. Net effect: exactly one debit, one payment, regardless of races.</p>
 *
 * <p>Connections are opened per operation (correct and thread-safe); a
 * production deployment would front this with a pool such as HikariCP.</p>
 */
public class JdbcPaymentRepository implements PaymentRepository, AutoCloseable {

    /** Distinct in-mem DB name so we never collide with {@code DatabaseUtil}'s testdb. */
    public static final String DEFAULT_URL =
            "jdbc:h2:mem:paymentdb;DB_CLOSE_DELAY=-1;MODE=MySQL";

    private final String url;
    private final String user;
    private final String password;
    private final AtomicLong sequence = new AtomicLong(1);

    /** Holds the in-mem DB alive for the repo's lifetime (no-op cost for file/MySQL URLs). */
    private final Connection keeper;

    public JdbcPaymentRepository() {
        this(DEFAULT_URL, "sa", "");
    }

    public JdbcPaymentRepository(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
        try {
            this.keeper = DriverManager.getConnection(url, user, password);
            initSchema();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot open payment DB: " + url, e);
        }
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    private void initSchema() throws SQLException {
        try (Connection c = conn(); var st = c.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS accounts (
                    user_id   VARCHAR(50)   PRIMARY KEY,
                    balance   DECIMAL(18,8) NOT NULL,
                    currency  VARCHAR(10)   NOT NULL DEFAULT 'USDT'
                )
            """);
            st.execute("""
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

    /** Pre-fund an account in the default currency (USDT). */
    public void seedAccount(String userId, BigDecimal balance) {
        seedAccount(userId, balance, "USDT");
    }

    /** Pre-fund an account in a specific currency. */
    public void seedAccount(String userId, BigDecimal balance, String currency) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "MERGE INTO accounts (user_id, balance, currency) KEY(user_id) VALUES (?, ?, ?)")) {
            ps.setString(1, userId);
            ps.setBigDecimal(2, balance);
            ps.setString(3, currency);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("seedAccount failed for " + userId, e);
        }
    }

    public BigDecimal getBalance(String userId) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT balance FROM accounts WHERE user_id = ?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getBigDecimal(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("getBalance failed for " + userId, e);
        }
        throw new NoSuchElementException("Account not found: " + userId);
    }

    @Override
    public Optional<PaymentResponse> findByIdempotencyKey(String idempotencyKey) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT payment_id, order_id, status, amount " +
                 "FROM payments WHERE idempotency_key = ?")) {
            ps.setString(1, idempotencyKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(PaymentResponse.builder()
                        .paymentId(rs.getString("payment_id"))
                        .orderId(rs.getString("order_id"))
                        .status("PENDING")            // POST contract: accepted, async
                        .amount(rs.getBigDecimal("amount"))
                        .jobId("JOB_" + rs.getString("payment_id"))
                        .message("Payment accepted. Use job_id to poll status.")
                        .build());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("findByIdempotencyKey failed", e);
        }
    }

    @Override
    public PaymentResponse createPayment(PaymentRequest request) {
        long seq = sequence.getAndIncrement();
        String paymentId = "PAY_" + seq;

        Connection c = null;
        try {
            c = conn();
            c.setAutoCommit(false);

            // 1. Existence + currency check. Both are immutable for an account
            //    (no delete/currency-change API), so this is safe outside the
            //    balance-race critical section below.
            String acctCurrency = accountCurrency(c, request.getUserId());
            if (acctCurrency == null) {
                c.rollback();
                throw new NoSuchElementException("Account not found: " + request.getUserId());
            }
            if (!acctCurrency.equalsIgnoreCase(request.getCurrency())) {
                c.rollback();
                throw new CurrencyMismatchException(
                        "Account " + request.getUserId() + " holds " + acctCurrency
                        + ", cannot pay in " + request.getCurrency());
            }

            // 2. Atomic conditional debit — the WHERE clause is the race guard.
            //    Existence is already confirmed, so rows == 0 here means only
            //    one thing: insufficient balance.
            int rows;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE accounts SET balance = balance - ? " +
                    "WHERE user_id = ? AND balance >= ?")) {
                ps.setBigDecimal(1, request.getAmount());
                ps.setString(2, request.getUserId());
                ps.setBigDecimal(3, request.getAmount());
                rows = ps.executeUpdate();
            }
            if (rows == 0) {
                c.rollback();
                throw new InsufficientBalanceException(
                        "Insufficient balance for userId=" + request.getUserId());
            }

            // 3. Persist the payment — UNIQUE(idempotency_key) is the concurrency backstop.
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO payments " +
                    "(payment_id, order_id, user_id, amount, status, idempotency_key) " +
                    "VALUES (?, ?, ?, ?, 'PENDING', ?)")) {
                ps.setString(1, paymentId);
                ps.setString(2, request.getOrderId());
                ps.setString(3, request.getUserId());
                ps.setBigDecimal(4, request.getAmount());
                ps.setString(5, request.getIdempotencyKey());
                ps.executeUpdate();
            }

            c.commit();
            return PaymentResponse.builder()
                    .paymentId(paymentId)
                    .orderId(request.getOrderId())
                    .status("PENDING")
                    .amount(request.getAmount())
                    .jobId("JOB_" + paymentId)
                    .message("Payment accepted. Use job_id to poll status.")
                    .build();

        } catch (SQLIntegrityConstraintViolationException dup) {
            // Lost an idempotency-key race: rollback undoes THIS debit, then we
            // return the payment the winning transaction committed.
            rollbackQuietly(c);
            return findByIdempotencyKey(request.getIdempotencyKey())
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency conflict but no committed payment found", dup));
        } catch (SQLException e) {
            rollbackQuietly(c);
            // H2 surfaces UNIQUE violations as a generic SQLException in some
            // paths — treat a known duplicate key as an idempotent replay too.
            if (isDuplicateKey(e)) {
                return findByIdempotencyKey(request.getIdempotencyKey())
                        .orElseThrow(() -> new IllegalStateException(
                                "Idempotency conflict but no committed payment found", e));
            }
            throw new IllegalStateException("createPayment failed", e);
        } finally {
            closeQuietly(c);
        }
    }

    @Override
    public boolean deductBalance(String userId, BigDecimal amount) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE accounts SET balance = balance - ? " +
                 "WHERE user_id = ? AND balance >= ?")) {
            ps.setBigDecimal(1, amount);
            ps.setString(2, userId);
            ps.setBigDecimal(3, amount);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("deductBalance failed for " + userId, e);
        }
    }

    @Override
    public void close() {
        try { if (keeper != null && !keeper.isClosed()) keeper.close(); }
        catch (SQLException ignored) { }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** The account's currency, or {@code null} if the account does not exist. */
    private String accountCurrency(Connection c, String userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT currency FROM accounts WHERE user_id = ?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private boolean isDuplicateKey(SQLException e) {
        // H2 unique-constraint violation = SQLState 23505 / error code 23505.
        String s = e.getSQLState();
        return "23505".equals(s) || "23000".equals(s) || e.getErrorCode() == 23505;
    }

    private void rollbackQuietly(Connection c) {
        if (c != null) try { c.rollback(); } catch (SQLException ignored) { }
    }

    private void closeQuietly(Connection c) {
        if (c != null) try { c.setAutoCommit(true); c.close(); } catch (SQLException ignored) { }
    }
}
