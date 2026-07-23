package com.binance.trading.engine;

import com.binance.trading.model.Order;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Order book with HashMap-based duplicate detection.
 * Maps to LC-217 (Contains Duplicate) and LC-347 (Top K Frequent Elements).
 *
 * <h2>Retention model</h2>
 *
 * All three structures are bounded to {@code retention} entries. Counts that must
 * survive eviction are kept in O(1) counters rather than by retaining objects:
 *
 * <ul>
 *   <li>{@code orders}           — orderId → first Order, most recent {@code retention} ids</li>
 *   <li>{@code orderIdFrequency} — orderId → submission count, evicted alongside {@code orders}</li>
 *   <li>{@code recentOrders}     — rolling window of the last {@code retention} submissions</li>
 *   <li>{@code totalSubmissions} / {@code uniqueSubmissions} — exact all-time counts, O(1) memory</li>
 * </ul>
 *
 * <h2>Why bounded</h2>
 *
 * These three collections were previously unbounded. Under a long-running daemon at
 * ~17 orders/sec they accumulated 11.36M Order objects over 7.6 days, exhausted the heap,
 * and drove the JVM into a Full GC death spiral — every collection reclaimed nothing because
 * every object was still strongly reachable from here. Full analysis:
 * {@code docs/incident-2026-07-14-gc-death-spiral/RCA-zh-TW.md}
 *
 * <h2>What eviction costs</h2>
 *
 * Duplicate detection is exact only within the retained window. {@code TradingEngine}
 * generates duplicates by stepping back 2, 4 or 6 counter values, so the default retention
 * of {@value #DEFAULT_RETENTION} leaves a margin of roughly 1600x over the widest possible
 * duplicate distance. An id evicted and then resubmitted would be counted as new; at the
 * engine's actual duplicate distance this cannot occur.
 */
public class OrderBook {

    /** Entries retained per structure. Sized far above the engine's duplicate distance (max 6). */
    public static final int DEFAULT_RETENTION = 10_000;

    private final int retention;

    // BOUNDED-BY: evicted in insertion order by addOrder() once size exceeds `retention`
    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    // BOUNDED-BY: evicted alongside `orders`, so it can never hold more ids than `orders`
    private final Map<String, Integer> orderIdFrequency = new ConcurrentHashMap<>();

    // BOUNDED-BY: rolling window capped at `retention`; guarded by `evictionLock`
    private final Deque<Order> recentOrders = new ArrayDeque<>();

    // BOUNDED-BY: one id per entry in `orders`, so it shares that bound; guarded by `evictionLock`
    private final Deque<String> idInsertionOrder = new ArrayDeque<>();

    /** Guards the two deques. The maps stay concurrent so reads remain lock-free. */
    private final Object evictionLock = new Object();

    /** All-time counts, kept as counters so they survive eviction in O(1) memory. */
    private final AtomicLong totalSubmissions  = new AtomicLong();
    private final AtomicLong uniqueSubmissions = new AtomicLong();

    public OrderBook() {
        this(DEFAULT_RETENTION);
    }

    public OrderBook(int retention) {
        if (retention < 1) {
            throw new IllegalArgumentException("retention must be >= 1, got " + retention);
        }
        this.retention = retention;
    }

    /**
     * Adds an order. Returns true if the order_id is new, false if it is a duplicate.
     *
     * <p>Retained memory is O(retention), independent of how many orders have been submitted.
     */
    public boolean addOrder(Order order) {
        String id = order.getOrderId();

        totalSubmissions.incrementAndGet();
        orderIdFrequency.merge(id, 1, Integer::sum);

        boolean isNew = orders.putIfAbsent(id, order) == null;
        if (isNew) {
            uniqueSubmissions.incrementAndGet();
        }

        synchronized (evictionLock) {
            recentOrders.addLast(order);
            while (recentOrders.size() > retention) {
                recentOrders.removeFirst();
            }

            if (isNew) {
                idInsertionOrder.addLast(id);
                while (idInsertionOrder.size() > retention) {
                    String evicted = idInsertionOrder.removeFirst();
                    orders.remove(evicted);
                    orderIdFrequency.remove(evicted);
                }
            }
        }

        return isNew;
    }

    // ── LC-217: Contains Duplicate ────────────────────────────────────────────

    public boolean hasDuplicates() {
        return orderIdFrequency.values().stream().anyMatch(c -> c > 1);
    }

    public List<String> findDuplicateOrderIds() {
        return orderIdFrequency.entrySet().stream()
            .filter(e -> e.getValue() > 1)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    // ── LC-347: Top K Frequent ────────────────────────────────────────────────

    public List<String> getTopKDuplicates(int k) {
        return orderIdFrequency.entrySet().stream()
            .filter(e -> e.getValue() > 1)
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(k)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public Map<String, Integer> getOrderIdFrequency() {
        return Collections.unmodifiableMap(orderIdFrequency);
    }

    /** The retained window — the last {@code retention} submissions, not all-time history. */
    public Collection<Order> getAllOrders() {
        synchronized (evictionLock) { return new ArrayList<>(recentOrders); }
    }

    public Order getOrder(String orderId)      { return orders.get(orderId); }

    /** All-time submissions including duplicates. Exact regardless of eviction. */
    public long totalOrderCount()              { return totalSubmissions.get(); }

    /** All-time distinct order_ids. Exact regardless of eviction. */
    public long uniqueOrderCount()             { return uniqueSubmissions.get(); }

    /** Entries currently retained. Never exceeds {@link #getRetention()}. */
    public int retainedOrderCount() {
        synchronized (evictionLock) { return recentOrders.size(); }
    }

    public int getRetention()                  { return retention; }

    public void clear() {
        synchronized (evictionLock) {
            orders.clear();
            orderIdFrequency.clear();
            recentOrders.clear();
            idInsertionOrder.clear();
            totalSubmissions.set(0);
            uniqueSubmissions.set(0);
        }
    }
}
