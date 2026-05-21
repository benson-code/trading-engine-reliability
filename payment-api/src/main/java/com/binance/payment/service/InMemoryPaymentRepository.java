package com.binance.payment.service;

import com.binance.payment.model.PaymentRequest;
import com.binance.payment.model.PaymentResponse;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory {@link PaymentRepository} — the first runnable implementation of the
 * payment backend (P1). Backs the real {@link PaymentService} so the API can be
 * exercised end-to-end without an external database.
 *
 * <p>Guarantees that matter for QA demos:</p>
 * <ul>
 *   <li><b>Idempotency, exactly-once:</b> {@code createPayment} runs inside
 *       {@code computeIfAbsent} keyed by idempotency key, so concurrent retries
 *       of the same key deduct the balance exactly once and return one
 *       {@code payment_id}.</li>
 *   <li><b>Atomic balance deduction:</b> {@code deductBalance} uses
 *       {@code ConcurrentHashMap.compute} as a check-and-set; an insufficient
 *       balance leaves the account untouched (no partial debit).</li>
 * </ul>
 *
 * <p>The {@link PaymentRepository} interface is the seam: swapping this for a
 * JDBC/H2-backed implementation (P2) is a one-line change in {@code Main}.</p>
 */
public class InMemoryPaymentRepository implements PaymentRepository {

    /** Starting balance auto-provisioned for any user not explicitly seeded. */
    public static final BigDecimal DEFAULT_BALANCE = new BigDecimal("1000000");

    private final ConcurrentHashMap<String, PaymentResponse> byIdempotencyKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BigDecimal>      balances         = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1);

    /** Pre-fund an account (used by tests / explicit setup). */
    public void seedAccount(String userId, BigDecimal balance) {
        balances.put(userId, balance);
    }

    public BigDecimal getBalance(String userId) {
        return balances.getOrDefault(userId, DEFAULT_BALANCE);
    }

    @Override
    public Optional<PaymentResponse> findByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(byIdempotencyKey.get(idempotencyKey));
    }

    @Override
    public PaymentResponse createPayment(PaymentRequest request) {
        // computeIfAbsent makes the create body run exactly once per idempotency
        // key, even when concurrent retries race here simultaneously.
        return byIdempotencyKey.computeIfAbsent(request.getIdempotencyKey(), key -> {
            if (!deductBalance(request.getUserId(), request.getAmount())) {
                throw new InsufficientBalanceException(
                        "Insufficient balance for userId=" + request.getUserId());
            }
            long seq = sequence.getAndIncrement();
            return PaymentResponse.builder()
                    .paymentId("PAY_" + seq)
                    .orderId(request.getOrderId())
                    .status("PENDING")
                    .amount(request.getAmount())
                    .jobId("JOB_" + seq)
                    .message("Payment accepted. Use job_id to poll status.")
                    .build();
        });
    }

    @Override
    public boolean deductBalance(String userId, BigDecimal amount) {
        // compute() is atomic per key: the read-compare-write runs under the
        // bin lock, so two threads cannot both pass the sufficiency check.
        // deducted[] is written inside that locked remapping, so its result is
        // consistent with the balance actually stored.
        boolean[] deducted = {false};
        balances.compute(userId, (u, current) -> {
            BigDecimal bal = (current == null) ? DEFAULT_BALANCE : current;
            if (bal.compareTo(amount) >= 0) {
                deducted[0] = true;
                return bal.subtract(amount);
            }
            return bal;
        });
        return deducted[0];
    }
}
