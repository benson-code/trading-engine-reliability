package com.binance.trading.engine;

import com.binance.trading.model.Order;
import com.binance.trading.model.OrderStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Core trading engine: two threads (BUY / SELL) alternate using Semaphores.
 * Maps to LC-1115 (Print FooBar Alternately).
 *
 * BUY-THREAD  acquires buySemaphore  → generates BUY order  → releases sellSemaphore
 * SELL-THREAD acquires sellSemaphore → generates SELL order → releases buySemaphore
 * → strict BUY→SELL→BUY→SELL alternation guaranteed by Semaphore handoff
 */
public class TradingEngine {

    private static final String SYMBOL        = "BTCUSDT";
    private static final BigDecimal BASE_PRICE = new BigDecimal("95000.00");

    private final OrderBook  orderBook;
    private final OrderCache orderCache;

    private final Semaphore buySemaphore  = new Semaphore(1);
    private final Semaphore sellSemaphore = new Semaphore(0);

    private final AtomicInteger              orderCounter   = new AtomicInteger(0);
    private final AtomicLong                 totalGenerated = new AtomicLong(0);
    private final AtomicLong                 buyCount       = new AtomicLong(0);
    private final AtomicLong                 sellCount      = new AtomicLong(0);
    private final AtomicReference<BigDecimal> lastPrice     = new AtomicReference<>(BASE_PRICE);

    private final AtomicBoolean        running       = new AtomicBoolean(false);
    private volatile Consumer<Order>  orderListener = null; // set by Main to broadcast via WebSocket
    private Thread buyThread;
    private Thread sellThread;

    private final Random random;
    private final int    intervalMs;
    private final double duplicateProbability;

    public TradingEngine(OrderBook orderBook, OrderCache orderCache,
                         int intervalMs, double duplicateProbability) {
        this.orderBook            = orderBook;
        this.orderCache           = orderCache;
        this.intervalMs           = intervalMs;
        this.duplicateProbability = duplicateProbability;
        this.random               = new Random();
    }

    public TradingEngine() {
        this(new OrderBook(), new OrderCache(1000), 200, 0.05);
    }

    public void setOrderListener(Consumer<Order> listener) {
        this.orderListener = listener;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return; // BUG-01 fix: atomic check-and-set

        buyThread = new Thread(() -> {
            while (running.get()) {
                try {
                    buySemaphore.acquire();
                    if (!running.get()) { sellSemaphore.release(); break; }
                    Order order = generateOrder("BUY");
                    orderBook.addOrder(order);
                    orderCache.put(order.getOrderId(), order);
                    totalGenerated.incrementAndGet();
                    buyCount.incrementAndGet();
                    if (orderListener != null) orderListener.accept(order);
                    sellSemaphore.release();
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "BUY-THREAD");

        sellThread = new Thread(() -> {
            while (running.get()) {
                try {
                    sellSemaphore.acquire();
                    if (!running.get()) { buySemaphore.release(); break; }
                    Order order = generateOrder("SELL");
                    orderBook.addOrder(order);
                    orderCache.put(order.getOrderId(), order);
                    totalGenerated.incrementAndGet();
                    sellCount.incrementAndGet();
                    if (orderListener != null) orderListener.accept(order);
                    buySemaphore.release();
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "SELL-THREAD");

        buyThread.setDaemon(true);
        sellThread.setDaemon(true);
        buyThread.start();
        sellThread.start();
    }

    public void stop() {
        running.set(false);
        buySemaphore.release();
        sellSemaphore.release();
        if (buyThread  != null) buyThread.interrupt();
        if (sellThread != null) sellThread.interrupt();
    }

    private Order generateOrder(String type) {
        int num = orderCounter.incrementAndGet();

        String orderId;
        if (random.nextDouble() < duplicateProbability && num > 10) {
            // BUG-08 fix: step back by multiples of 2 so we always land on the same thread's counter
            // BUY gets odd counters (1,3,5...), SELL gets even counters (2,4,6...) due to strict alternation
            int stepsBack = (random.nextInt(3) + 1) * 2; // 2, 4, or 6
            int recentNum = Math.max(1, num - stepsBack);
            orderId = String.format("ORD-%s-%06d", type, recentNum);
        } else {
            orderId = String.format("ORD-%s-%06d", type, num);
        }

        double fluctuation = 1.0 + (random.nextDouble() - 0.5) * 0.04;
        BigDecimal price = BASE_PRICE.multiply(new BigDecimal(fluctuation))
            .setScale(2, RoundingMode.HALF_UP);
        lastPrice.set(price);

        BigDecimal amount = new BigDecimal(0.001 + random.nextDouble() * 1.999)
            .setScale(8, RoundingMode.HALF_UP);

        return Order.builder()
            .orderId(orderId)
            .type(type)
            .symbol(SYMBOL)
            .amount(amount.toPlainString())
            .price(price)
            .status(OrderStatus.PENDING)
            .timestamp(System.currentTimeMillis())
            .threadName(Thread.currentThread().getName())
            .build();
    }

    public OrderBook   getOrderBook()      { return orderBook; }
    public OrderCache  getOrderCache()     { return orderCache; }
    public boolean     isRunning()         { return running.get(); }
    public long        getTotalGenerated() { return totalGenerated.get(); }
    public long        getBuyCount()       { return buyCount.get(); }
    public long        getSellCount()      { return sellCount.get(); }
    public BigDecimal  getLastPrice()      { return lastPrice.get(); }
}
