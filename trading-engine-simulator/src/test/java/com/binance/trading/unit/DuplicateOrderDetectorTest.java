package com.binance.trading.unit;

import com.binance.trading.engine.OrderBook;
import com.binance.trading.model.Order;
import com.binance.trading.model.OrderStatus;
import io.qameta.allure.*;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pattern 1 — HashMap counting for duplicate order detection.
 *
 * LeetCode mapping:
 *   LC-217  Contains Duplicate          → hasDuplicates()
 *   LC-347  Top K Frequent Elements     → getTopKDuplicates(k)
 *
 * Real Binance risk: a duplicate order_id causes double-deduction of funds.
 */
@Epic("Trading Engine")
@Feature("Pattern 1 — Duplicate Detection: HashMap Counting (LC-217 / LC-347)")
class DuplicateOrderDetectorTest {

    private OrderBook orderBook;

    @BeforeEach
    void setUp() {
        orderBook = new OrderBook();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  LC-217: Contains Duplicate
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Story("LC-217 — Contains Duplicate")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("[LC-217] hasDuplicates() → false when all order_ids are unique")
    void noDuplicates_whenAllOrderIdsAreUnique() {
        orderBook.addOrder(buildOrder("ORD-BUY-000001", "BUY",  "0.10000000"));
        orderBook.addOrder(buildOrder("ORD-SELL-000002", "SELL", "0.20000000"));
        orderBook.addOrder(buildOrder("ORD-BUY-000003", "BUY",  "0.05000000"));

        assertFalse(orderBook.hasDuplicates(),
            "hasDuplicates() must return false when all order_ids are unique");
        assertEquals(3, orderBook.uniqueOrderCount());
        assertEquals(3, orderBook.totalOrderCount());
    }

    @Test
    @Story("LC-217 — Contains Duplicate")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("[LC-217] hasDuplicates() → true when same order_id submitted twice (double-submit attack)")
    void detectsDuplicate_whenSameOrderIdSubmittedTwice() {
        String duplicateId = "ORD-BUY-000001";
        orderBook.addOrder(buildOrder(duplicateId, "BUY", "0.10000000"));
        orderBook.addOrder(buildOrder(duplicateId, "BUY", "0.10000000")); // duplicate!

        assertTrue(orderBook.hasDuplicates(),
            "hasDuplicates() must return true when same order_id submitted twice");
    }

    @Test
    @Story("LC-217 — Contains Duplicate")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("[LC-217] addOrder() returns false for duplicate order_id — prevents double processing")
    void addOrder_returnsFalse_forDuplicateOrderId() {
        String orderId     = "ORD-BUY-999999";
        boolean firstInsert  = orderBook.addOrder(buildOrder(orderId, "BUY", "1.00000000"));
        boolean secondInsert = orderBook.addOrder(buildOrder(orderId, "BUY", "1.00000000"));

        assertTrue(firstInsert,   "First submission: new order_id must return true");
        assertFalse(secondInsert, "Second submission: duplicate order_id must return false");
        assertEquals(1, orderBook.uniqueOrderCount(), "Unique count must remain 1");
        assertEquals(2, orderBook.totalOrderCount(),  "Total count must be 2 (both recorded)");
    }

    // ════════════════════════════════════════════════════════════════════════
    //  LC-347: Top K Frequent Elements
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Story("LC-347 — Top K Frequent Elements")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("[LC-347] getTopKDuplicates(2) returns the 2 most frequently duplicated order_ids")
    void topKDuplicates_returnsCorrectTopK() {
        // ORD-001 submitted 3 times (highest frequency)
        orderBook.addOrder(buildOrder("ORD-001", "BUY", "0.1"));
        orderBook.addOrder(buildOrder("ORD-001", "BUY", "0.1"));
        orderBook.addOrder(buildOrder("ORD-001", "BUY", "0.1"));

        // ORD-002 submitted 2 times
        orderBook.addOrder(buildOrder("ORD-002", "SELL", "0.2"));
        orderBook.addOrder(buildOrder("ORD-002", "SELL", "0.2"));

        // ORD-003 submitted 1 time (no duplicate)
        orderBook.addOrder(buildOrder("ORD-003", "BUY", "0.3"));

        List<String> top2 = orderBook.getTopKDuplicates(2);

        assertEquals(2, top2.size(),        "Must return exactly 2 results");
        assertEquals("ORD-001", top2.get(0), "ORD-001 (count=3) must be #1");
        assertEquals("ORD-002", top2.get(1), "ORD-002 (count=2) must be #2");
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Frequency Map & Batch Testing
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Story("HashMap Frequency Map")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("getOrderIdFrequency() correctly counts occurrences per order_id")
    void frequencyMap_correctlyCounts() {
        orderBook.addOrder(buildOrder("ORD-A", "BUY",  "0.5"));
        orderBook.addOrder(buildOrder("ORD-A", "BUY",  "0.5")); // 2 times
        orderBook.addOrder(buildOrder("ORD-B", "SELL", "0.3")); // 1 time

        Map<String, Integer> freq = orderBook.getOrderIdFrequency();

        assertEquals(2, freq.get("ORD-A"), "ORD-A submitted twice → count=2");
        assertEquals(1, freq.get("ORD-B"), "ORD-B submitted once  → count=1");
    }

    @Test
    @Story("Batch Duplicate Detection")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("findDuplicateOrderIds() correctly identifies 10 duplicates out of 110 submissions")
    void findDuplicates_inLargeBatch() {
        // 100 unique orders
        for (int i = 1; i <= 100; i++) {
            orderBook.addOrder(buildOrder("ORD-" + i, "BUY", "0.10000000"));
        }
        // Re-submit first 10 — these become duplicates
        for (int i = 1; i <= 10; i++) {
            orderBook.addOrder(buildOrder("ORD-" + i, "BUY", "0.10000000"));
        }

        List<String> duplicates = orderBook.findDuplicateOrderIds();

        assertEquals(10,  duplicates.size(),           "Must detect exactly 10 duplicate IDs");
        assertEquals(100, orderBook.uniqueOrderCount(), "Unique count must stay at 100");
        assertEquals(110, orderBook.totalOrderCount(),  "Total submitted must be 110");
        assertTrue(orderBook.hasDuplicates());
    }

    private Order buildOrder(String orderId, String type, String amount) {
        return Order.builder()
            .orderId(orderId)
            .type(type)
            .symbol("BTCUSDT")
            .amount(amount)
            .price(new BigDecimal("95000.00"))
            .status(OrderStatus.PENDING)
            .timestamp(System.currentTimeMillis())
            .build();
    }
}
