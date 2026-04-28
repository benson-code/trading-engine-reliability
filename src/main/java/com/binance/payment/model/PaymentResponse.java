package com.binance.payment.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public class PaymentResponse {

    @JsonProperty("payment_id")
    private String paymentId;

    @JsonProperty("order_id")
    private String orderId;

    @JsonProperty("status")
    private String status;

    @JsonProperty("amount")
    private BigDecimal amount;

    @JsonProperty("message")
    private String message;

    @JsonProperty("job_id")
    private String jobId;

    public PaymentResponse() {}

    public PaymentResponse(String paymentId, String orderId, String status,
                           BigDecimal amount, String message, String jobId) {
        this.paymentId = paymentId;
        this.orderId = orderId;
        this.status = status;
        this.amount = amount;
        this.message = message;
        this.jobId = jobId;
    }

    public static Builder builder() { return new Builder(); }

    public String getPaymentId()  { return paymentId; }
    public String getOrderId()    { return orderId; }
    public String getStatus()     { return status; }
    public BigDecimal getAmount() { return amount; }
    public String getMessage()    { return message; }
    public String getJobId()      { return jobId; }

    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }
    public void setOrderId(String orderId)     { this.orderId = orderId; }
    public void setStatus(String status)       { this.status = status; }
    public void setAmount(BigDecimal amount)   { this.amount = amount; }
    public void setMessage(String message)     { this.message = message; }
    public void setJobId(String jobId)         { this.jobId = jobId; }

    public static class Builder {
        private String paymentId;
        private String orderId;
        private String status;
        private BigDecimal amount;
        private String message;
        private String jobId;

        public Builder paymentId(String v)  { this.paymentId = v; return this; }
        public Builder orderId(String v)    { this.orderId = v; return this; }
        public Builder status(String v)     { this.status = v; return this; }
        public Builder amount(BigDecimal v) { this.amount = v; return this; }
        public Builder message(String v)    { this.message = v; return this; }
        public Builder jobId(String v)      { this.jobId = v; return this; }

        public PaymentResponse build() {
            return new PaymentResponse(paymentId, orderId, status, amount, message, jobId);
        }
    }
}
