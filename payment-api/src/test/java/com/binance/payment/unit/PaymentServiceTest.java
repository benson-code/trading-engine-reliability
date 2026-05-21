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

    @Test
    @Story("Validation — length bounds match the DB schema")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Idempotency key longer than 100 chars is rejected at the service layer")
    void should_reject_overlong_idempotency_key() {
        PaymentRequest request = PaymentRequest.builder()
                .orderId("ORD_X")
                .userId("USER_001")
                .amount(new BigDecimal("10.00"))
                .currency("USDT")
                .idempotencyKey("K".repeat(101))   // schema is VARCHAR(100)
                .build();

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> paymentService.processPayment(request));
        assertTrue(ex.getMessage().contains("Idempotency key too long"));
        verify(repository, never()).createPayment(any());
    }

    @Test
    @Story("Validation — amount precision (DECIMAL(18,8))")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Amount with more than 8 significant decimals is rejected (no silent truncation)")
    void should_reject_amount_over_8_decimals() {
        PaymentRequest request = PaymentRequest.builder()
                .orderId("ORD_P")
                .userId("USER_001")
                .amount(new BigDecimal("0.123456789"))   // 9 decimals
                .currency("USDT")
                .idempotencyKey("PREC_KEY")
                .build();

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> paymentService.processPayment(request));
        assertTrue(ex.getMessage().contains("precision"));
        verify(repository, never()).createPayment(any());
    }

    @Test
    @Story("Validation — amount precision (DECIMAL(18,8))")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("Trailing zeros beyond 8 decimals are NOT over-rejected (100.500000000 = 100.5)")
    void should_accept_amount_with_trailing_zero_padding() {
        when(repository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(repository.createPayment(any()))
                .thenReturn(PaymentResponse.builder().paymentId("PAY_OK").status("PENDING").build());

        PaymentRequest request = PaymentRequest.builder()
                .orderId("ORD_TZ")
                .userId("USER_001")
                .amount(new BigDecimal("100.500000000"))  // 9 places but only 1 significant
                .currency("USDT")
                .idempotencyKey("TZ_KEY")
                .build();

        PaymentResponse result = paymentService.processPayment(request);

        assertEquals("PAY_OK", result.getPaymentId());
        verify(repository, times(1)).createPayment(any());
    }

    @Test
    @Story("Validation")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("Missing currency is rejected")
    void should_reject_missing_currency() {
        PaymentRequest request = PaymentRequest.builder()
                .orderId("ORD_C")
                .userId("USER_001")
                .amount(new BigDecimal("10.00"))
                .currency(null)
                .idempotencyKey("CCY_KEY")
                .build();

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> paymentService.processPayment(request));
        assertTrue(ex.getMessage().contains("Currency is required"));
        verify(repository, never()).createPayment(any());
    }

    @Test
    @Story("Validation — length bounds match the DB schema")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("User ID longer than 50 chars is rejected at the service layer")
    void should_reject_overlong_user_id() {
        PaymentRequest request = PaymentRequest.builder()
                .orderId("ORD_Y")
                .userId("U".repeat(51))            // schema is VARCHAR(50)
                .amount(new BigDecimal("10.00"))
                .currency("USDT")
                .idempotencyKey("OK_KEY")
                .build();

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> paymentService.processPayment(request));
        assertTrue(ex.getMessage().contains("User ID too long"));
        verify(repository, never()).createPayment(any());
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
