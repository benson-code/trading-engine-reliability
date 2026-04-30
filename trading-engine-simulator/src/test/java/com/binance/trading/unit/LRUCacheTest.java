package com.binance.trading.unit;

import com.binance.trading.engine.OrderCache;
import com.binance.trading.model.Order;
import com.binance.trading.model.OrderStatus;
import io.qameta.allure.*;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pattern 2 — LRU Cache backed by LinkedHashMap.
 * Maps directly to LC-146 (LRU Cache).
 *
 * Key behaviours under test:
 *   1. put/get basic operations
 *   2. LRU eviction: eldest entry is evicted when capacity exceeded
 *   3. Access order: get() promotes entry to most-recently-used
 *   4. Cache metrics: hit rate tracking
 */
@Epic("Trading Engine")
@Feature("Pattern 2 — LRU Cache: LinkedHashMap (LC-146)")
class LRUCacheTest {

    // ════════════════════════════════════════════════════════════════════════
    //  Basic Operations
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Story("LC-146 — Basic Operations")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("[LC-146] put() + get() stores and retrieves an order correctly")
    void putAndGet_storesAndRetrievesOrder() {
        OrderCache cache = new OrderCache(100);
        Order order = buildOrder("ORD-001");
        cache.put("ORD-001", order);

        Order retrieved = cache.get("ORD-001");

        assertNotNull(retrieved,                    "Cache hit must not return null");
        assertEquals("ORD-001", retrieved.getOrderId());
        assertEquals(1, cache.getHitCount(),        "get() on existing key → hitCount=1");
    }

    @Test
    @Story("LC-146 — Cache Miss")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("[LC-146] get() returns null for a key not in cache (cache miss)")
    void get_returnsNull_forCacheMiss() {
        OrderCache cache = new OrderCache(100);

        Order result = cache.get("NON-EXISTENT");

        assertNull(result,                         "Cache miss must return null");
        assertEquals(1, cache.getMissCount(),       "get() on missing key → missCount=1");
        assertEquals(0, cache.getHitCount(),        "No hits should be recorded");
    }

    // ════════════════════════════════════════════════════════════════════════
    //  LRU Eviction — Core LC-146 Behaviour
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Story("LC-146 — LRU Eviction")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("[LC-146] Least recently used entry is evicted when capacity exceeded")
    void evictsLRU_whenCapacityExceeded() {
        OrderCache cache = new OrderCache(3);

        cache.put("ORD-A", buildOrder("ORD-A")); // access order: [A]
        cache.put("ORD-B", buildOrder("ORD-B")); // access order: [A, B]
        cache.put("ORD-C", buildOrder("ORD-C")); // access order: [A, B, C]
        cache.get("ORD-A");                       // access A → order: [B, C, A]
        cache.put("ORD-D", buildOrder("ORD-D")); // evict LRU=B → [C, A, D]

        assertNotNull(cache.get("ORD-A"), "A was recently accessed — must NOT be evicted");
        assertNotNull(cache.get("ORD-C"), "C was not evicted — must still be in cache");
        assertNotNull(cache.get("ORD-D"), "D was just inserted — must be in cache");
        assertNull(cache.get("ORD-B"),    "B was LRU — must be evicted");
    }

    @Test
    @Story("LC-146 — LRU Eviction")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("[LC-146] Cache size never exceeds configured capacity after many inserts")
    void cacheSize_neverExceedsCapacity() {
        int capacity = 5;
        OrderCache cache = new OrderCache(capacity);

        for (int i = 0; i < 100; i++) {
            cache.put("ORD-" + i, buildOrder("ORD-" + i));
        }

        assertTrue(cache.size() <= capacity,
            "Cache size must never exceed capacity=" + capacity + ", got size=" + cache.size());
        assertEquals(capacity, cache.size(), "Cache must be exactly at capacity after overflow");
    }

    @Test
    @Story("LC-146 — Access Order Promotion")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("[LC-146] Accessing an older entry promotes it — preventing its eviction")
    void accessingOlderEntry_preventsEviction() {
        OrderCache cache = new OrderCache(2);

        cache.put("ORD-OLD", buildOrder("ORD-OLD")); // oldest
        cache.put("ORD-NEW", buildOrder("ORD-NEW")); // newer
        cache.get("ORD-OLD");                         // promote OLD → now most recent
        cache.put("ORD-NEWEST", buildOrder("ORD-NEWEST")); // evict LRU = NEW

        assertNotNull(cache.get("ORD-OLD"),    "OLD was promoted — must survive eviction");
        assertNotNull(cache.get("ORD-NEWEST"), "NEWEST was just inserted — must be in cache");
        assertNull(cache.get("ORD-NEW"),       "NEW was LRU after promotion — must be evicted");
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Cache Metrics (Hit Rate)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Story("Cache Metrics — Hit Rate")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("Hit rate = 1.0 when all gets are cache hits")
    void hitRate_isOne_whenAllGetsAreHits() {
        OrderCache cache = new OrderCache(100);
        cache.put("ORD-X", buildOrder("ORD-X"));
        cache.get("ORD-X"); // hit
        cache.get("ORD-X"); // hit
        cache.get("ORD-X"); // hit

        assertEquals(1.0, cache.getHitRate(), 0.001,
            "3/3 hits → hit rate must be 1.0");
    }

    @Test
    @Story("Cache Metrics — Hit Rate")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("Hit rate = 0.0 when all gets are cache misses")
    void hitRate_isZero_whenAllGetsAreMisses() {
        OrderCache cache = new OrderCache(100);
        cache.get("MISS-1"); // miss
        cache.get("MISS-2"); // miss

        assertEquals(0.0, cache.getHitRate(), 0.001,
            "0/2 hits → hit rate must be 0.0");
    }

    @Test
    @Story("Cache Metrics — Hit Rate")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("Hit rate = 0.5 when equal hits and misses")
    void hitRate_isHalf_whenEqualHitsAndMisses() {
        OrderCache cache = new OrderCache(100);
        cache.put("ORD-Y", buildOrder("ORD-Y"));
        cache.get("ORD-Y");  // hit
        cache.get("MISS-1"); // miss

        assertEquals(0.5, cache.getHitRate(), 0.001,
            "1 hit + 1 miss → hit rate must be 0.5");
    }

    @Test
    @Story("Cache Metrics — Hit Rate")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("Hit rate = 0.0 before any get() calls")
    void hitRate_isZero_beforeAnyGets() {
        OrderCache cache = new OrderCache(100);
        assertEquals(0.0, cache.getHitRate(), 0.001,
            "No gets yet → hit rate must be 0.0 (not NaN or exception)");
    }

    private Order buildOrder(String orderId) {
        return Order.builder()
            .orderId(orderId)
            .type("BUY")
            .symbol("BTCUSDT")
            .amount("0.10000000")
            .price(new BigDecimal("95000.00"))
            .status(OrderStatus.PENDING)
            .timestamp(System.currentTimeMillis())
            .build();
    }
}
