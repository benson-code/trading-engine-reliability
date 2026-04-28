package com.binance.payment.service;

import com.binance.payment.model.PaymentRequest;
import com.binance.payment.model.PaymentResponse;

import java.math.BigDecimal;
import java.util.Optional;

public interface PaymentRepository {

    Optional<PaymentResponse> findByIdempotencyKey(String idempotencyKey);

    PaymentResponse createPayment(PaymentRequest request);

    boolean deductBalance(String userId, BigDecimal amount);
}
