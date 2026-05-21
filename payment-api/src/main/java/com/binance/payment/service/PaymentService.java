package com.binance.payment.service;

import com.binance.payment.model.PaymentRequest;
import com.binance.payment.model.PaymentResponse;

import java.math.BigDecimal;

public class PaymentService {

    private final PaymentRepository repository;

    public PaymentService(PaymentRepository repository) {
        this.repository = repository;
    }

    public PaymentResponse processPayment(PaymentRequest request) {
        validate(request);

        // Idempotency: return cached result if key already processed
        return repository.findByIdempotencyKey(request.getIdempotencyKey())
                .orElseGet(() -> repository.createPayment(request));
    }

    /**
     * Whether a payment already exists for this idempotency key. Lets the API
     * layer answer 200 (idempotent replay) vs 202 (newly accepted) without
     * altering {@link #processPayment}'s idempotency guarantee.
     */
    public boolean isAlreadyProcessed(String idempotencyKey) {
        return idempotencyKey != null && !idempotencyKey.isBlank()
                && repository.findByIdempotencyKey(idempotencyKey).isPresent();
    }

    /** Max lengths mirror the DB schema (see {@link JdbcPaymentRepository#initSchema}). */
    static final int MAX_IDEMPOTENCY_KEY = 100;
    static final int MAX_USER_ID         = 50;
    static final int MAX_ORDER_ID        = 50;
    static final int MAX_CURRENCY        = 10;

    private void validate(PaymentRequest request) {
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank()) {
            throw new IllegalArgumentException("Idempotency key is required");
        }
        if (request.getUserId() == null || request.getUserId().isBlank()) {
            throw new IllegalArgumentException("User ID is required");
        }
        if (request.getCurrency() == null || request.getCurrency().isBlank()) {
            throw new IllegalArgumentException("Currency is required");
        }
        // Length bounds match the persistence schema so violations surface as
        // 400 VALIDATION_ERROR here, never as opaque 5xx from a SQL truncation.
        if (request.getIdempotencyKey().length() > MAX_IDEMPOTENCY_KEY) {
            throw new IllegalArgumentException(
                    "Idempotency key too long (max " + MAX_IDEMPOTENCY_KEY + ")");
        }
        if (request.getUserId().length() > MAX_USER_ID) {
            throw new IllegalArgumentException(
                    "User ID too long (max " + MAX_USER_ID + ")");
        }
        if (request.getOrderId() != null && request.getOrderId().length() > MAX_ORDER_ID) {
            throw new IllegalArgumentException(
                    "Order ID too long (max " + MAX_ORDER_ID + ")");
        }
        if (request.getCurrency() != null && request.getCurrency().length() > MAX_CURRENCY) {
            throw new IllegalArgumentException(
                    "Currency code too long (max " + MAX_CURRENCY + ")");
        }
    }
}
