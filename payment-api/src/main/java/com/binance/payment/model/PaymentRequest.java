package com.binance.payment.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public class PaymentRequest {

    @JsonProperty("order_id")
    private String orderId;

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("amount")
    private BigDecimal amount;

    @JsonProperty("currency")
    private String currency;

    @JsonProperty("idempotency_key")
    private String idempotencyKey;

    public PaymentRequest() {}

    public PaymentRequest(String orderId, String userId, BigDecimal amount,
                          String currency, String idempotencyKey) {
        this.orderId = orderId;
        this.userId = userId;
        this.amount = amount;
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
    }

    public static Builder builder() { return new Builder(); }

    public String getOrderId()        { return orderId; }
    public String getUserId()         { return userId; }
    public BigDecimal getAmount()     { return amount; }
    public String getCurrency()       { return currency; }
    public String getIdempotencyKey() { return idempotencyKey; }

    public void setOrderId(String orderId)               { this.orderId = orderId; }
    public void setUserId(String userId)                 { this.userId = userId; }
    public void setAmount(BigDecimal amount)             { this.amount = amount; }
    public void setCurrency(String currency)             { this.currency = currency; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public static class Builder {
        private String orderId;
        private String userId;
        private BigDecimal amount;
        private String currency;
        private String idempotencyKey;

        public Builder orderId(String v)        { this.orderId = v; return this; }
        public Builder userId(String v)         { this.userId = v; return this; }
        public Builder amount(BigDecimal v)     { this.amount = v; return this; }
        public Builder currency(String v)       { this.currency = v; return this; }
        public Builder idempotencyKey(String v) { this.idempotencyKey = v; return this; }

        public PaymentRequest build() {
            return new PaymentRequest(orderId, userId, amount, currency, idempotencyKey);
        }
    }
}
