package com.binance.trading.db;

import io.qameta.allure.*;
import org.junit.jupiter.api.*;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Binance QA-style DB validation against live MySQL.
 *
 * Run with: mvn test -pl trading-engine-simulator -Dgroups=db-validation
 * Skipped automatically when DB_URL env var is not set (safe for CI without MySQL).
 *
 * Validates 6 concerns a payment QA engineer checks after any data migration or
 * engine run: nulls, enum values, BUY/SELL ratio, thread-type pairing,
 * duplicate flag accuracy, and order-id format consistency.
 */
@Epic("Trading Engine")
@Feature("DB Validation — Binance QA Style")
@Tag("db-validation")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DBValidationTest {

    private static final String URL  = System.getenv("DB_URL")  != null
            ? System.getenv("DB_URL")
            : "jdbc:mysql://localhost:3306/binance_test_db?useSSL=false&allowPublicKeyRetrieval=true";
    private static final String USER = System.getenv("DB_USER") != null
            ? System.getenv("DB_USER") : "binance_user";
    private static final String PASS = System.getenv("DB_PASSWORD") != null
            ? System.getenv("DB_PASSWORD") : "BinanceTest2026";

    private static Connection conn;

    @BeforeAll
    static void connect() throws Exception {
        try {
            conn = DriverManager.getConnection(URL, USER, PASS);
        } catch (SQLException e) {
            // Skip all tests gracefully if MySQL is not available (e.g. plain CI)
            conn = null;
        }
        assumeConnected();
    }

    @AfterAll
    static void disconnect() throws Exception {
        if (conn != null && !conn.isClosed()) conn.close();
    }

    private static void assumeConnected() {
        org.junit.jupiter.api.Assumptions.assumeTrue(conn != null,
                "MySQL not available — skipping DB validation tests");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private long queryLong(String sql) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    // ── Test 1: Data Integrity ────────────────────────────────────────────────

    @Test
    @Order(1)
    @Story("Data Integrity")
    @Severity(SeverityLevel.BLOCKER)
    @DisplayName("① No NULL or empty values in required fields")
    void noNullsInRequiredFields() throws SQLException {
        long nullCount = queryLong("""
            SELECT SUM(
                (order_id IS NULL OR order_id = '') +
                (type     IS NULL OR type     = '') +
                (price    IS NULL OR price    <= 0) +
                (amount   IS NULL OR amount   <= 0) +
                (status   IS NULL OR status   = '') +
                (timestamp IS NULL OR timestamp <= 0)
            ) FROM orders
        """);
        assertEquals(0, nullCount,
            "Required fields must never be NULL or empty — found " + nullCount + " violation(s)");
    }

    // ── Test 2: Enum Validation ───────────────────────────────────────────────

    @Test
    @Order(2)
    @Story("Data Integrity")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("② type column only contains 'BUY' or 'SELL'")
    void typeEnumIsValid() throws SQLException {
        long invalidTypes = queryLong(
            "SELECT COUNT(*) FROM orders WHERE type NOT IN ('BUY','SELL')");
        assertEquals(0, invalidTypes,
            "type must be BUY or SELL only — found " + invalidTypes + " invalid row(s)");
    }

    // ── Test 3: BUY/SELL Alternation ─────────────────────────────────────────

    @Test
    @Order(3)
    @Story("Business Rules — Thread Alternation")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("③ BUY and SELL counts differ by at most 1 (strict alternation)")
    void buySellRatioIsBalanced() throws SQLException {
        long buy  = queryLong("SELECT COUNT(*) FROM orders WHERE type = 'BUY'");
        long sell = queryLong("SELECT COUNT(*) FROM orders WHERE type = 'SELL'");
        assertTrue(Math.abs(buy - sell) <= 1,
            "Strict alternation: |BUY - SELL| must be ≤ 1, got BUY=" + buy + " SELL=" + sell);
    }

    // ── Test 4: Thread–Type Pairing ───────────────────────────────────────────

    @Test
    @Order(4)
    @Story("Business Rules — Thread Safety")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("④ BUY-THREAD only produces BUY orders; SELL-THREAD only produces SELL orders")
    void threadTypePairingIsConsistent() throws SQLException {
        long mismatch = queryLong("""
            SELECT COUNT(*) FROM orders
            WHERE (thread_name = 'BUY-THREAD'  AND type = 'SELL')
               OR (thread_name = 'SELL-THREAD' AND type = 'BUY')
        """);
        assertEquals(0, mismatch,
            "Thread-type mismatch: BUY-THREAD must only produce BUY, found " + mismatch + " violation(s)");
    }

    // ── Test 5: Duplicate Flag Accuracy (current session only) ───────────────

    @Test
    @Order(5)
    @Story("Duplicate Detection")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("⑤ is_duplicate flag accurate within current engine session")
    void duplicateFlagIsAccurate() throws SQLException {
        // Detect current session: engine restarts by re-using ORD-BUY-000001.
        // The last appearance of that ID marks the start of the latest session.
        long sessionStartId = queryLong("""
            SELECT MAX(id) FROM orders
            WHERE order_id IN ('ORD-BUY-000001', 'ORD-SELL-000002')
        """);

        // Within this session: is_duplicate=1 on a single-occurrence order = false positive
        long falsePositives = queryLong("""
            SELECT COUNT(*) FROM orders o
            WHERE o.id >= """ + sessionStartId + """

              AND o.is_duplicate = 1
              AND (SELECT COUNT(*) FROM orders o2
                   WHERE o2.order_id = o.order_id AND o2.id >= """ + sessionStartId + """
                  ) = 1
        """);
        assertEquals(0, falsePositives,
            "is_duplicate=1 on single-occurrence orders in current session: " + falsePositives);

        // Within this session: non-first duplicate occurrence still has is_duplicate=0 = false negative
        long falseNegatives = queryLong("""
            SELECT COUNT(*) FROM orders o
            WHERE o.id >= """ + sessionStartId + """

              AND o.is_duplicate = 0
              AND (SELECT COUNT(*) FROM orders o2
                   WHERE o2.order_id = o.order_id AND o2.id >= """ + sessionStartId + """
                  ) > 1
              AND o.id != (
                  SELECT MIN(o3.id) FROM orders o3
                  WHERE o3.order_id = o.order_id AND o3.id >= """ + sessionStartId + """

              )
        """);
        assertEquals(0, falseNegatives,
            "is_duplicate=0 on repeat occurrences within current session: " + falseNegatives);
    }

    // ── Test 6: Order ID Format vs Type ──────────────────────────────────────

    @Test
    @Order(6)
    @Story("Data Consistency")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("⑥ Order ID prefix matches order type (ORD-BUY-* → BUY, ORD-SELL-* → SELL)")
    void orderIdPrefixMatchesType() throws SQLException {
        long mismatch = queryLong("""
            SELECT COUNT(*) FROM orders
            WHERE (order_id LIKE 'ORD-BUY-%'  AND type != 'BUY')
               OR (order_id LIKE 'ORD-SELL-%' AND type != 'SELL')
        """);
        assertEquals(0, mismatch,
            "Order ID prefix must match type — found " + mismatch + " mismatch(es)");
    }

    // ── Test 7: Amount Precision ──────────────────────────────────────────────

    @Test
    @Order(7)
    @Story("Data Integrity")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("⑦ Amount precision does not exceed 8 decimal places")
    void amountPrecisionMax8Decimals() throws SQLException {
        long overPrecision = queryLong("""
            SELECT COUNT(*) FROM orders
            WHERE CHAR_LENGTH(
                SUBSTRING_INDEX(CAST(amount AS CHAR), '.', -1)
            ) > 8
        """);
        assertEquals(0, overPrecision,
            "Amount must not exceed 8 decimal places — found " + overPrecision + " violation(s)");
    }

    // ── Test 8: Sanity — DB has data ─────────────────────────────────────────

    @Test
    @Order(8)
    @Story("Sanity Check")
    @Severity(SeverityLevel.BLOCKER)
    @DisplayName("⑧ Database contains at least 1 order (engine ran successfully)")
    void databaseHasOrders() throws SQLException {
        long total = queryLong("SELECT COUNT(*) FROM orders");
        assertTrue(total > 0, "orders table is empty — engine may not have run");
        System.out.printf("[DB] Total orders: %d | Unique IDs: %d | Duplicates: %d%n",
            total,
            queryLong("SELECT COUNT(DISTINCT order_id) FROM orders"),
            queryLong("SELECT SUM(is_duplicate) FROM orders"));
    }
}
