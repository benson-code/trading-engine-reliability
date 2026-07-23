package com.binance.trading.endurance;

import com.binance.trading.engine.OrderBook;
import com.binance.trading.model.Order;
import com.binance.trading.model.OrderStatus;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Endurance tests — the time axis that functional tests do not have.
 *
 * <p>The 2026-07 incident ran 84 functional tests green throughout while the service
 * accumulated 11.36M unreclaimable Order objects and drove the JVM into a Full GC death
 * spiral. Functional tests execute in milliseconds against tens of rows; at that scale a
 * bounded and an unbounded collection are indistinguishable, because both answer every
 * functional question correctly. The defect was only ever visible as growth over time.
 *
 * <p>These tests assert the property that separates the two: <b>when input volume grows
 * by 10x, does the retained set grow by 10x?</b>
 *
 * <p>See {@code docs/incident-2026-07-14-gc-death-spiral/RCA-zh-TW.md}.
 */
@Epic("Trading Engine")
@Feature("Endurance — retained set must not grow with input volume")
@Tag("endurance")
class OrderBookRetentionTest {

    private static final int RETENTION = 1_000;

    // ════════════════════════════════════════════════════════════════════════
    // Structural bound — deterministic, no GC involved
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Story("Retained entries stay capped as input volume grows")
    @Severity(SeverityLevel.BLOCKER)
    @DisplayName("[ENDURANCE] Retained set stays at the cap while submissions grow 100x")
    void retainedSetStaysCappedRegardlessOfVolume() {
        OrderBook book = new OrderBook(RETENTION);

        feed(book, 1_000);
        int afterFirst = book.retainedOrderCount();

        feed(book, 99_000);                       // 100x the input
        int afterHundredfold = book.retainedOrderCount();

        assertEquals(afterFirst, afterHundredfold,
            "Retained set grew with input volume — the collections are unbounded. "
          + "This is the exact defect that caused the 2026-07 GC death spiral.");

        assertTrue(afterHundredfold <= RETENTION,
            "Retained set (" + afterHundredfold + ") exceeded the cap (" + RETENTION + ")");

        // All-time counters must stay exact even though the objects were evicted.
        assertEquals(100_000, book.totalOrderCount(),
            "All-time submission count must survive eviction");
    }

    @Test
    @Story("Every bounded structure is capped, not just the visible one")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("[ENDURANCE] All three structures stay bounded — window, id map, frequency map")
    void allThreeStructuresStayBounded() {
        OrderBook book = new OrderBook(RETENTION);
        feed(book, 50_000);

        assertTrue(book.retainedOrderCount() <= RETENTION,
            "Rolling window unbounded: " + book.retainedOrderCount());

        assertTrue(book.getAllOrders().size() <= RETENTION,
            "getAllOrders() returned more than the retention window: " + book.getAllOrders().size());

        assertTrue(book.getOrderIdFrequency().size() <= RETENTION,
            "Frequency map unbounded: " + book.getOrderIdFrequency().size()
          + " — this was the third strong-reference path in the incident");

        assertEquals(50_000, book.uniqueOrderCount(),
            "All-time unique count must survive eviction");
    }

    // ════════════════════════════════════════════════════════════════════════
    // Heap bound — what the incident actually exhausted
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Story("Post-collection heap occupancy does not scale with input volume")
    @Severity(SeverityLevel.BLOCKER)
    @DisplayName("[ENDURANCE] Retained heap after GC does not grow linearly with submissions")
    void retainedHeapDoesNotGrowLinearly() {
        OrderBook book = new OrderBook(RETENTION);

        feed(book, 20_000);
        long baseline = retainedHeapBytes();

        feed(book, 180_000);                      // total is now 10x the baseline input
        long after = retainedHeapBytes();

        long growth = after - baseline;

        // A bounded book retains the same objects at both measurements, so growth should sit
        // around zero. An unbounded one would retain 10x. The 50% band absorbs JIT, class
        // loading and allocation noise while staying far below a linear signal.
        assertTrue(growth < baseline / 2,
            String.format(
                "Retained heap grew %,d KB (%,d KB -> %,d KB) while input grew 10x. "
              + "Retention is not holding: the collections behave as unbounded.",
                growth / 1024, baseline / 1024, after / 1024));
    }

    // ════════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════════

    private static void feed(OrderBook book, int count) {
        for (int i = 0; i < count; i++) {
            book.addOrder(order("ORD-" + i));
        }
    }

    private static Order order(String id) {
        return Order.builder()
            .orderId(id)
            .type("BUY")
            .symbol("BTCUSDT")
            .amount("0.10000000")
            .price(new BigDecimal("65000.00"))
            .status(OrderStatus.PENDING)
            .timestamp(System.currentTimeMillis())
            .threadName(Thread.currentThread().getName())
            .build();
    }

    /**
     * Heap still occupied once the collector has run — that residue is the retained set,
     * which is what a leak grows. Measuring before collection would mostly report
     * short-lived garbage and drown the signal.
     *
     * <p>{@code System.gc()} is only a hint, so this collects repeatedly and confirms via
     * {@code GarbageCollectorMXBean} that collections actually happened; if none did, the
     * measurement is not trustworthy and the test fails rather than reporting a false pass.
     */
    private static long retainedHeapBytes() {
        long before = totalGcCount();

        for (int i = 0; i < 4; i++) {
            System.gc();
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for GC", e);
            }
        }

        assertTrue(totalGcCount() > before,
            "No garbage collection was observed, so retained heap cannot be measured. "
          + "Re-run with -XX:+UseSerialGC or a JVM that honours System.gc().");

        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static long totalGcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
            .mapToLong(bean -> Math.max(bean.getCollectionCount(), 0))
            .sum();
    }
}
