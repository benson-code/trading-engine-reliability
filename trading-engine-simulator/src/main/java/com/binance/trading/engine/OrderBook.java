package com.binance.trading.engine;

import com.binance.trading.model.Order;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Order book with HashMap-based duplicate detection.
 * Maps to LC-217 (Contains Duplicate) and LC-347 (Top K Frequent Elements).
 *
 * - orders:           orderId → first Order (unique primary store)
 * - orderIdFrequency: orderId → submission count (the "HashMap counting" pattern)
 * - allOrders:        every submission including duplicates (for analysis)
 */
public class OrderBook {

    private final Map<String, Order> orders            = new ConcurrentHashMap<>();
    private final Map<String, Integer> orderIdFrequency = new ConcurrentHashMap<>();
    private final List<Order> allOrders                 = Collections.synchronizedList(new ArrayList<>());

    /**
     * Adds an order. Returns true if the order_id is new, false if it is a duplicate.
     */
    public boolean addOrder(Order order) {
        String id = order.getOrderId();
        allOrders.add(order);
        orderIdFrequency.merge(id, 1, Integer::sum);
        return orders.putIfAbsent(id, order) == null;
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

    public Collection<Order> getAllOrders()    { return Collections.unmodifiableList(allOrders); }
    public Order getOrder(String orderId)      { return orders.get(orderId); }
    public int totalOrderCount()               { return allOrders.size(); }
    public int uniqueOrderCount()              { return orders.size(); }

    public void clear() {
        orders.clear();
        orderIdFrequency.clear();
        allOrders.clear();
    }
}
