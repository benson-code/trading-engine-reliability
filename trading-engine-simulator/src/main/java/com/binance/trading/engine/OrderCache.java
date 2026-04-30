package com.binance.trading.engine;

import com.binance.trading.model.Order;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LRU Cache backed by LinkedHashMap — maps directly to LC-146 (LRU Cache).
 *
 * LinkedHashMap(capacity, loadFactor, accessOrder=true) keeps entries sorted
 * by access time. removeEldestEntry evicts the LRU entry when capacity is exceeded.
 */
public class OrderCache {

    private final int capacity;
    private final LinkedHashMap<String, Order> cache;
    private int hitCount  = 0;
    private int missCount = 0;

    public OrderCache(int capacity) {
        this.capacity = capacity;
        this.cache = new LinkedHashMap<String, Order>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Order> eldest) {
                return size() > capacity;
            }
        };
    }

    public synchronized void put(String orderId, Order order) {
        cache.put(orderId, order);
    }

    public synchronized Order get(String orderId) {
        Order order = cache.get(orderId);
        if (order != null) hitCount++;
        else              missCount++;
        return order;
    }

    public synchronized boolean containsKey(String orderId) {
        return cache.containsKey(orderId);
    }

    public synchronized int size()       { return cache.size(); }
    public synchronized int getCapacity(){ return capacity; }
    public synchronized int getHitCount(){ return hitCount; }
    public synchronized int getMissCount(){ return missCount; }

    public synchronized double getHitRate() {
        int total = hitCount + missCount;
        return total == 0 ? 0.0 : (double) hitCount / total;
    }

    public synchronized void clear() {
        cache.clear();
        hitCount  = 0;
        missCount = 0;
    }
}
