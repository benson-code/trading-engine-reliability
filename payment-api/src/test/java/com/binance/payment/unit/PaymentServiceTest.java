package com.binance.payment.unit;

import com.binance.payment.model.PaymentRequest;
import com.binance.payment.model.PaymentResponse;
import com.binance.payment.service.PaymentRepository;
import com.binance.payment.service.PaymentService;
import io.qameta.allure.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Epic("Payment Service")
@Feature("Input Validation & Idempotency Logic")
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository repository;

    @InjectMocks
    private PaymentService paymentService;

    // ─── Validation Tests ───────────────────────────────────────────────────

    @Test
    @Story("Validation")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Negative amount throws IllegalArgumentException")
    void should_reject_negative_amount() {
        PaymentRequest request = PaymentRequest.builder()
                .orderId("ORD_001")
                .userId("USER_001")
                .amount(new BigDecimal("-50.00"))
                .currency("USDT")
                .idempotencyKey("KEY_001")
                .build();

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> paymentService.processPayment(request));

        assertTrue(ex.getMessage().contains("positive"));
        verify(repository, never()).createPayment(any());
    }

    @Test
    @Story("Validation")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Zero amount throws IllegalArgumentException")
    void should_reject_zero_amount() {
        PaymentRequest request = PaymentRequest.builder()
                .orderId("ORD_002")
                .userId("USER_001")
                .amount(BigDecimal.ZERO)
                .currency("USDT")
                .idempotencyKey("KEY_002")
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> paymentService.processPayment(request));
    }

    @Test
    @Story("Validation")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("Missing idempotency key throws IllegalArgumentException")
    void should_reject_missing_idempotency_key() {
        PaymentRequest request = PaymentRequest.builder()
                .orderId("ORD_003")
                .userId("USER_001")
                .amount(new BigDecimal("100.00"))
                .currency("USDT")
                .idempotencyKey("")
                .build();

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> paymentService.processPayment(request));

        assertTrue(ex.getMessage().contains("Idempotency"));
    }

    // ─── Idempotency Tests ───────────────────────────────────────────────────

    @Test
    @Story("Idempotency")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Duplicate idempotency key returns cached response without creating new payment")
    void should_return_cached_response_for_duplicate_idempotency_key() {
        String idempotencyKey = "IDEM_KEY_EXISTING";
        PaymentResponse cachedResponse = PaymentResponse.builder()
                .paymentId("PAY_EXISTING_001")
                .status("SUCCESS")
                .amount(new BigDecimal("100.00"))
                .build();

        when(repository.findByIdempotencyKey(idempotencyKey))
                .thenReturn(Optional.of(cachedResponse));

        PaymentRequest request = PaymentRequest.builder()
                .orderId("ORD_004")
                .userId("USER_001")
                .amount(new BigDecimal("100.00"))
                .currency("USDT")
                .idempotencyKey(idempotencyKey)
                .build();

        PaymentResponse result = paymentService.processPayment(request);

        assertEquals("PAY_EXISTING_001", result.getPaymentId());
        assertEquals("SUCCESS", result.getStatus());
        // Critical: createPayment must NOT be called for duplicate keys
        verify(repository, never()).createPayment(any());
    }

    @Test
    @Story("Idempotency")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("New unique idempotency key creates a new payment record")
    void should_create_new_payment_for_unique_idempotency_key() {
        String idempotencyKey = "IDEM_KEY_NEW";
        PaymentResponse newResponse = PaymentResponse.builder()
                .paymentId("PAY_NEW_001")
                .status("PENDING")
                .jobId("JOB_001")
                .build();

        when(repository.findByIdempotencyKey(idempotencyKey))
                .thenReturn(Optional.empty());
        when(repository.createPayment(any()))
                .thenReturn(newResponse);

        PaymentRequest request = PaymentRequest.builder()
                .orderId("ORD_005")
                .userId("USER_001")
                .amount(new BigDecimal("200.00"))
                .currency("USDT")
                .idempotencyKey(idempotencyKey)
                .build();

        PaymentResponse result = paymentService.processPayment(request);

        assertEquals("PAY_NEW_001", result.getPaymentId());
        assertEquals("PENDING", result.getStatus());
        verify(repository, times(1)).createPayment(any());
    }
}
