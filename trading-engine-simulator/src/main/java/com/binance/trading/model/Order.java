package com.binance.trading.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public class Order {

    @JsonProperty("order_id")
    private String orderId;

    @JsonProperty("type")
    private String type;            // BUY or SELL

    @JsonProperty("symbol")
    private String symbol;          // e.g. BTCUSDT

    @JsonProperty("amount")
    private String amount;          // kept as String for Pattern 3 (amount validation testing)

    @JsonProperty("price")
    private BigDecimal price;

    @JsonProperty("status")
    private OrderStatus status;

    @JsonProperty("timestamp")
    private long timestamp;

    @JsonProperty("thread_name")
    private String threadName;      // which thread generated this order (Pattern 4)

    public Order() {}

    private Order(Builder b) {
        this.orderId    = b.orderId;
        this.type       = b.type;
        this.symbol     = b.symbol;
        this.amount     = b.amount;
        this.price      = b.price;
        this.status     = b.status;
        this.timestamp  = b.timestamp;
        this.threadName = b.threadName;
    }

    public String getOrderId()    { return orderId; }
    public String getType()       { return type; }
    public String getSymbol()     { return symbol; }
    public String getAmount()     { return amount; }
    public BigDecimal getPrice()  { return price; }
    public OrderStatus getStatus(){ return status; }
    public long getTimestamp()    { return timestamp; }
    public String getThreadName() { return threadName; }

    public void setOrderId(String orderId)       { this.orderId    = orderId; }
    public void setType(String type)             { this.type       = type; }
    public void setSymbol(String symbol)         { this.symbol     = symbol; }
    public void setAmount(String amount)         { this.amount     = amount; }
    public void setPrice(BigDecimal price)       { this.price      = price; }
    public void setStatus(OrderStatus status)    { this.status     = status; }
    public void setTimestamp(long timestamp)     { this.timestamp  = timestamp; }
    public void setThreadName(String threadName) { this.threadName = threadName; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String orderId;
        private String type;
        private String symbol     = "BTCUSDT";
        private String amount;
        private BigDecimal price;
        private OrderStatus status    = OrderStatus.PENDING;
        private long timestamp        = System.currentTimeMillis();
        private String threadName;

        public Builder orderId(String v)    { this.orderId    = v; return this; }
        public Builder type(String v)       { this.type       = v; return this; }
        public Builder symbol(String v)     { this.symbol     = v; return this; }
        public Builder amount(String v)     { this.amount     = v; return this; }
        public Builder price(BigDecimal v)  { this.price      = v; return this; }
        public Builder status(OrderStatus v){ this.status     = v; return this; }
        public Builder timestamp(long v)    { this.timestamp  = v; return this; }
        public Builder threadName(String v) { this.threadName = v; return this; }

        public Order build() { return new Order(this); }
    }

    @Override
    public String toString() {
        return "Order{id='" + orderId + "', type='" + type + "', amount='" + amount
            + "', thread='" + threadName + "'}";
    }
}
