package com.binance.payment.service;

import com.binance.payment.model.PaymentRequest;
import com.binance.payment.model.PaymentResponse;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
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
    /** Currency auto-provisioned for any user not explicitly seeded. */
    public static final String DEFAULT_CURRENCY = "USDT";

    /** Idempotency keys retained. Beyond this the oldest is dropped — see the field javadoc. */
    public static final int IDEMPOTENCY_RETENTION = 100_000;

    /**
     * idempotencyKey → the response that key produced.
     *
     * <p>BOUNDED-BY: {@link #trackAndEvictKey(String)} drops the oldest key once
     * {@link #IDEMPOTENCY_RETENTION} is exceeded.
     *
     * <p><b>This bound is a deliberate trade-off, not a free win.</b> A retry of a key that
     * has aged out is treated as a new payment and charges the account again. Real idempotency
     * stores bound by time (a TTL of hours to days) rather than by count, because what matters
     * is the retry window, not the entry count. A count bound is used here because this
     * implementation holds no clock-driven eviction and is the demo path only —
     * {@link com.binance.payment.service.JdbcPaymentRepository} is the durable one
     * ({@code Main} selects it with {@code repoMode=jdbc}).
     *
     * <p>At 100k keys the window covers far more than any realistic client retry burst, so the
     * exposure is theoretical here. It would not be in production, where this should become a
     * TTL-indexed store.
     */
    private final ConcurrentHashMap<String, PaymentResponse> byIdempotencyKey = new ConcurrentHashMap<>();

    // BOUNDED-BY: one key per entry in `byIdempotencyKey`, so it shares that bound;
    // guarded by `keyEvictionLock`
    private final Deque<String> keyInsertionOrder = new ArrayDeque<>();

    private final Object keyEvictionLock = new Object();

    // BOUNDED-BY: nothing, honestly — this is a NOT-evicted decision, not a bounded one, and
    // the distinction matters enough to state plainly rather than let the declaration imply
    // safety it does not have.
    //
    // Eviction is refused because dropping an entry resets that user's balance to
    // DEFAULT_BALANCE on the next read, i.e. invents money. Losing the bound is the lesser
    // harm in a demo repository; losing the money is not.
    //
    // Growth is one entry per distinct userId ever seen, and `deductBalance` uses compute(),
    // which auto-provisions on first sight — so a client that supplies fresh userIds grows this
    // map with request volume. Bounding it needs an account lifecycle (registration, closure)
    // that this class does not model. JdbcPaymentRepository, the durable path, holds accounts
    // in a table with a strict foreign key and does not have this shape.
    private final ConcurrentHashMap<String, BigDecimal>      balances         = new ConcurrentHashMap<>();

    // BOUNDED-BY: seeded accounts only — unlike `balances`, nothing auto-provisions here;
    // getCurrency() reads through a default without writing, so this grows solely via
    // seedAccount(). Not evicted, for the same reason `balances` is not.
    private final ConcurrentHashMap<String, String>          currencies       = new ConcurrentHashMap<>();

    private final AtomicLong sequence = new AtomicLong(1);

    /** Pre-fund an account in the default currency (USDT). */
    public void seedAccount(String userId, BigDecimal balance) {
        seedAccount(userId, balance, DEFAULT_CURRENCY);
    }

    /** Pre-fund an account in a specific currency. */
    public void seedAccount(String userId, BigDecimal balance, String currency) {
        balances.put(userId, balance);
        currencies.put(userId, currency);
    }

    public BigDecimal getBalance(String userId) {
        return balances.getOrDefault(userId, DEFAULT_BALANCE);
    }

    public String getCurrency(String userId) {
        return currencies.getOrDefault(userId, DEFAULT_CURRENCY);
    }

    @Override
    public Optional<PaymentResponse> findByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(byIdempotencyKey.get(idempotencyKey));
    }

    @Override
    public PaymentResponse createPayment(PaymentRequest request) {
        // computeIfAbsent makes the create body run exactly once per idempotency
        // key, even when concurrent retries race here simultaneously.
        boolean[] created = { false };
        PaymentResponse response = byIdempotencyKey.computeIfAbsent(request.getIdempotencyKey(), key -> {
            created[0] = true;
            String acctCurrency = getCurrency(request.getUserId());
            if (!acctCurrency.equalsIgnoreCase(request.getCurrency())) {
                throw new CurrencyMismatchException(
                        "Account " + request.getUserId() + " holds " + acctCurrency
                        + ", cannot pay in " + request.getCurrency());
            }
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
        // Only genuinely new keys are tracked, so the deque holds exactly one entry per
        // retained response and cannot outgrow the map.
        if (created[0]) {
            trackAndEvictKey(request.getIdempotencyKey());
        }
        return response;
    }

    /** Records a newly stored key and drops the oldest once retention is exceeded. */
    private void trackAndEvictKey(String idempotencyKey) {
        synchronized (keyEvictionLock) {
            keyInsertionOrder.addLast(idempotencyKey);
            while (keyInsertionOrder.size() > IDEMPOTENCY_RETENTION) {
                byIdempotencyKey.remove(keyInsertionOrder.removeFirst());
            }
        }
    }

    /** Idempotency keys currently retained. Never exceeds {@link #IDEMPOTENCY_RETENTION}. */
    public int retainedIdempotencyKeyCount() {
        synchronized (keyEvictionLock) { return keyInsertionOrder.size(); }
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
