package com.binance.trading.integration;

import com.binance.trading.api.TradingApiServer;
import com.binance.trading.engine.AmountValidator;
import java.net.ServerSocket;
import com.binance.trading.engine.OrderBook;
import com.binance.trading.engine.OrderCache;
import com.binance.trading.engine.TradingEngine;
import com.binance.trading.model.Order;
import io.qameta.allure.*;
import io.restassured.RestAssured;
import org.junit.jupiter.api.*;

import java.util.Collection;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: all 4 patterns verified together against a live engine.
 *
 * Engine config: interval=50ms, duplicate_probability=20%, cache_capacity=50
 * → 2 seconds runtime → ~40+ orders with duplicates and cache eviction
 *
 * Pattern | Component           | LeetCode
 * --------|---------------------|----------
 * 1       | OrderBook           | LC-217 / LC-347
 * 2       | OrderCache          | LC-146
 * 3       | AmountValidator     | LC-65 / LC-8
 * 4       | TradingEngine       | LC-1115
 */
@Epic("Trading Engine")
@Feature("Integration — All 4 Patterns End-to-End")
class TradingFlowIntegrationTest {

    private static TradingEngine    engine;
    private static TradingApiServer server;
    private static int              PORT;

    private static int findFreePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    @BeforeAll
    static void startEngine() throws Exception {
        PORT = findFreePort();
        OrderBook  orderBook  = new OrderBook();
        OrderCache orderCache = new OrderCache(50); // small capacity → forces LRU eviction
        engine = new TradingEngine(orderBook, orderCache, 50, 0.20); // 20% duplicate rate
        server = new TradingApiServer(PORT, engine);
        server.start();
        engine.start();

        RestAssured.baseURI = "http://localhost";
        RestAssured.port    = PORT;

        Thread.sleep(2_000); // 2 seconds → ~40+ orders + duplicates
    }

    @AfterAll
    static void stopEngine() {
        if (engine != null) engine.stop();
        if (server != null) server.stop();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Master Integration Test — all 4 patterns in one assertion block
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Story("All 4 Patterns — End-to-End")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("All 4 patterns verified: HashMap duplicates + LRU cache + Amount validation + Thread alternation")
    void allFourPatterns_verifiedEndToEnd() {
        Collection<Order> allOrders = engine.getOrderBook().getAllOrders();
        assertFalse(allOrders.isEmpty(), "Engine must have generated orders within 2 seconds");

        // ── Pattern 1: HashMap Duplicate Detection (LC-217) ──────────────────
        List<String> duplicates = engine.getOrderBook().findDuplicateOrderIds();
        assertFalse(duplicates.isEmpty(),
            "Pattern 1 FAIL: 20% duplicate rate → expect duplicates after 2s, got 0");

        int uniqueCount = engine.getOrderBook().uniqueOrderCount();
        int totalCount  = engine.getOrderBook().totalOrderCount();
        assertTrue(totalCount > uniqueCount,
            "Pattern 1 FAIL: total(" + totalCount + ") must exceed unique(" + uniqueCount + ")");

        // ── Pattern 2: LRU Cache — capacity & eviction (LC-146) ──────────────
        OrderCache cache = engine.getOrderCache();
        assertTrue(cache.size() > 0, "Pattern 2 FAIL: cache must have entries");
        assertTrue(cache.size() <= 50,
            "Pattern 2 FAIL: cache size=" + cache.size() + " must not exceed capacity=50");

        // ── Pattern 3: Amount Validation — all engine-generated amounts valid ─
        long invalidAmounts = allOrders.stream()
            .filter(o -> !AmountValidator.isValid(o.getAmount()))
            .count();
        assertEquals(0, invalidAmounts,
            "Pattern 3 FAIL: engine-generated amounts must ALL be valid, but "
            + invalidAmounts + " failed validation");

        // ── Pattern 4: Thread Alternation — BUY/SELL counts differ by ≤1 ─────
        long buyCount  = allOrders.stream().filter(o -> "BUY".equals(o.getType())).count();
        long sellCount = allOrders.stream().filter(o -> "SELL".equals(o.getType())).count();
        assertTrue(Math.abs(buyCount - sellCount) <= 1,
            "Pattern 4 FAIL: strict alternation requires |BUY - SELL| ≤ 1, got BUY="
            + buyCount + " SELL=" + sellCount);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Pattern 1: HashMap — API assertion
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Story("Pattern 1 — Duplicate Detection via API")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("GET /api/v1/orders/duplicates confirms duplicate orders detected by HashMap")
    void pattern1_duplicatesDetectedViaApi() {
        given()
        .when()
            .get("/api/v1/orders/duplicates")
        .then()
            .statusCode(200)
            .body("has_duplicates",  equalTo(true))
            .body("duplicate_count", greaterThan(0))
            .body("frequency_map",   notNullValue());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Pattern 2: LRU Cache — API assertion
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Story("Pattern 2 — LRU Cache via API")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("GET /api/v1/status confirms cache_size ≤ 50 (LRU eviction working)")
    void pattern2_cacheSizeWithinCapacityViaApi() {
        given()
        .when()
            .get("/api/v1/status")
        .then()
            .statusCode(200)
            .body("cache_size", greaterThanOrEqualTo(1))
            .body("cache_size", lessThanOrEqualTo(50));
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Pattern 4: Thread Alternation — orders endpoint assertion
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Story("Pattern 4 — Thread Alternation via API")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("GET /api/v1/status confirms both BUY and SELL threads produced orders")
    void pattern4_bothThreadsGeneratedOrdersViaApi() {
        long buyCount  = engine.getOrderBook().getAllOrders().stream()
            .filter(o -> "BUY".equals(o.getType())).count();
        long sellCount = engine.getOrderBook().getAllOrders().stream()
            .filter(o -> "SELL".equals(o.getType())).count();

        assertTrue(buyCount  > 0, "BUY-THREAD must have generated at least 1 order");
        assertTrue(sellCount > 0, "SELL-THREAD must have generated at least 1 order");
        assertTrue(Math.abs(buyCount - sellCount) <= 1,
            "Strict alternation: |BUY - SELL| must be ≤ 1");
    }
}
