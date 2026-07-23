package com.binance.payment.endurance;

import com.binance.payment.model.PaymentRequest;
import com.binance.payment.service.InMemoryPaymentRepository;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Endurance tests for the payment module's retained state.
 *
 * <p>The GC death spiral in {@code trading-engine-simulator} came from collections that only
 * ever grew. Applying {@code tools/check-bounded-collections.sh} to this module surfaced the
 * same shape here: {@code byIdempotencyKey} accumulated one entry per unique key with no
 * removal path, and {@code PaymentApiServer.jobs} one entry per payment. Neither had failed
 * anything, because functional tests submit a handful of payments and finish in milliseconds.
 *
 * <p>See {@code docs/incident-2026-07-14-gc-death-spiral/RCA-zh-TW.md} and
 * {@code docs/checklists/resource-safety-zh-TW.md}.
 */
@Epic("Payment API")
@Feature("Endurance — retained state must not grow with request volume")
@Tag("endurance")
class PaymentRetentionTest {

    @Test
    @Story("Idempotency store stays capped as request volume grows")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("[ENDURANCE] Idempotency keys stay capped while unique keys grow past retention")
    void idempotencyStoreStaysCapped() {
        InMemoryPaymentRepository repo = new InMemoryPaymentRepository();
        repo.seedAccount("USER_ENDURANCE", new BigDecimal("100000000"));

        int overshoot = InMemoryPaymentRepository.IDEMPOTENCY_RETENTION + 5_000;
        for (int i = 0; i < overshoot; i++) {
            repo.createPayment(request("KEY-" + i));
        }

        assertEquals(InMemoryPaymentRepository.IDEMPOTENCY_RETENTION,
            repo.retainedIdempotencyKeyCount(),
            "Idempotency store grew past its cap — the map is behaving as unbounded");
    }

    @Test
    @Story("Retention is honoured exactly, and the newest keys are the ones kept")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("[ENDURANCE] Eviction drops the oldest keys and keeps the newest")
    void evictionDropsOldestKeepsNewest() {
        InMemoryPaymentRepository repo = new InMemoryPaymentRepository();
        repo.seedAccount("USER_ENDURANCE", new BigDecimal("100000000"));

        int cap = InMemoryPaymentRepository.IDEMPOTENCY_RETENTION;
        for (int i = 0; i < cap + 100; i++) {
            repo.createPayment(request("KEY-" + i));
        }

        assertTrue(repo.findByIdempotencyKey("KEY-" + (cap + 99)).isPresent(),
            "The most recent key must still be retained — a retry inside the window "
          + "has to replay rather than charge again");

        assertTrue(repo.findByIdempotencyKey("KEY-0").isEmpty(),
            "The oldest key should have been evicted; if it is still present the bound "
          + "is not actually being applied");
    }

    private static PaymentRequest request(String idempotencyKey) {
        PaymentRequest r = new PaymentRequest();
        r.setUserId("USER_ENDURANCE");
        r.setOrderId("ORDER-" + idempotencyKey);
        r.setAmount(new BigDecimal("1.00"));
        r.setCurrency("USDT");
        r.setIdempotencyKey(idempotencyKey);
        return r;
    }
}
