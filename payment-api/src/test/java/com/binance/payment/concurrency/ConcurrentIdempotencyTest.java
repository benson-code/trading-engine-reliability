package com.binance.payment.concurrency;

import com.binance.payment.model.PaymentRequest;
import com.binance.payment.service.InMemoryPaymentRepository;
import com.binance.payment.service.JdbcPaymentRepository;
import com.binance.payment.service.PaymentRepository;
import io.qameta.allure.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P3 / Finding F1 — proves both {@link PaymentRepository} implementations are
 * concurrency-safe: when N threads call {@code createPayment} with the SAME
 * idempotency key simultaneously, the account is debited <b>exactly once</b>
 * and a single logical payment results.
 *
 * <ul>
 *   <li>InMemory: guaranteed by {@code ConcurrentHashMap.computeIfAbsent}.</li>
 *   <li>JDBC: guaranteed by the {@code UNIQUE(idempotency_key)} constraint —
 *       losers roll back, which undoes their debit.</li>
 * </ul>
 */
@Epic("Payment System")
@Feature("Concurrency — Idempotency Under Race")
class ConcurrentIdempotencyTest {

    private static final int        THREADS = 16;
    private static final BigDecimal SEED    = new BigDecimal("1000000");
    private static final BigDecimal AMOUNT  = new BigDecimal("100");

    interface RepoFixture {
        PaymentRepository repo();
        void seed(String user, BigDecimal amount);
        BigDecimal balance(String user);
        void close();
        String label();
    }

    static java.util.stream.Stream<Arguments> repos() {
        RepoFixture inMemory = new RepoFixture() {
            final InMemoryPaymentRepository r = new InMemoryPaymentRepository();
            public PaymentRepository repo() { return r; }
            public void seed(String u, BigDecimal a) { r.seedAccount(u, a); }
            public BigDecimal balance(String u) { return r.getBalance(u); }
            public void close() { }
            public String label() { return "InMemoryPaymentRepository"; }
        };
        RepoFixture jdbc = new RepoFixture() {
            final JdbcPaymentRepository r = new JdbcPaymentRepository(
                    "jdbc:h2:mem:c_" + UUID.randomUUID().toString().replace("-", "")
                            + ";DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");
            public PaymentRepository repo() { return r; }
            public void seed(String u, BigDecimal a) { r.seedAccount(u, a); }
            public BigDecimal balance(String u) { return r.getBalance(u); }
            public void close() { r.close(); }
            public String label() { return "JdbcPaymentRepository"; }
        };
        return java.util.stream.Stream.of(Arguments.of(inMemory), Arguments.of(jdbc));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("repos")
    @Story("Idempotency under concurrent retries")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("N concurrent createPayment with same key → exactly one debit, one payment")
    void concurrent_same_key_debits_once(RepoFixture fx) throws Exception {
        try {
            String user = "U_RACE";
            fx.seed(user, SEED);

            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            CountDownLatch gate  = new CountDownLatch(1);
            Set<String> paymentIds   = Collections.synchronizedSet(new java.util.HashSet<>());
            Set<String> failures     = Collections.synchronizedSet(new java.util.HashSet<>());
            CountDownLatch done  = new CountDownLatch(THREADS);

            for (int i = 0; i < THREADS; i++) {
                pool.submit(() -> {
                    try {
                        gate.await();   // release all threads at once → max contention
                        PaymentRequest r = PaymentRequest.builder()
                                .orderId("O_RACE").userId(user)
                                .amount(AMOUNT).currency("USDT")
                                .idempotencyKey("SAME_KEY").build();
                        paymentIds.add(fx.repo().createPayment(r).getPaymentId());
                    } catch (Throwable t) {
                        failures.add(t.getClass().getSimpleName() + ": " + t.getMessage());
                    } finally {
                        done.countDown();
                    }
                });
            }
            gate.countDown();
            assertTrue(done.await(15, TimeUnit.SECONDS), "threads did not finish in time");
            pool.shutdownNow();

            assertTrue(failures.isEmpty(),
                    "no call should fail under the idempotency race, but saw: " + failures);
            assertEquals(1, paymentIds.size(),
                    "all concurrent retries must collapse to ONE payment_id, got: " + paymentIds);
            assertEquals(0, SEED.subtract(AMOUNT).compareTo(fx.balance(user)),
                    "account must be debited EXACTLY once despite " + THREADS + " concurrent calls");
        } finally {
            fx.close();
        }
    }
}
