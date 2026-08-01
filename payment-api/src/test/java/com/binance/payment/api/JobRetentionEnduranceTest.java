package com.binance.payment.api;

import com.binance.payment.service.InMemoryPaymentRepository;
import com.binance.payment.service.PaymentService;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Endurance test for {@code PaymentApiServer.jobs}.
 *
 * <p>{@link PaymentRetentionTest} covers the idempotency store; this covers the other bound
 * added at the same time. It lives in this package rather than {@code ..endurance} because
 * both the retention-injecting constructor and the counters it asserts on are package-private.
 *
 * <p>The bound is enforced by a deque: an accepted job is appended, and once the deque passes
 * retention the oldest id is popped and removed from the map. Settlement runs later on a timer
 * and writes the job's terminal state back. If that write can create a mapping rather than only
 * update one, it resurrects an id the deque no longer holds — and nothing will ever evict it,
 * because eviction only removes ids popped from the deque. The map then grows with request
 * volume again, which is the shape the whole checklist exists to prevent.
 *
 * <p>Asserting on {@link PaymentApiServer#liveJobCount()} rather than
 * {@link PaymentApiServer#retainedJobCount()} is the point: the deque stays at the cap by
 * construction, so it cannot see this.
 *
 * <p>See {@code docs/incident-2026-07-14-gc-death-spiral/RCA-zh-TW.md}.
 */
@Epic("Payment API")
@Feature("Endurance — retained state must not grow with request volume")
@Tag("endurance")
class JobRetentionEnduranceTest {

    /** Small enough that the send loop overshoots it many times over. */
    private static final int JOB_RETENTION = 5;

    private static final int PAYMENTS = 60;

    /**
     * The ordering under test is eviction-then-settlement, so this has to outlast the whole
     * send loop: a job evicted at request N + {@value #JOB_RETENTION} must still have its
     * settlement pending. A shorter delay settles each job while it is still in the map, where
     * a create-or-update write is indistinguishable from an update — which is exactly how this
     * test passed against the defect on the first attempt.
     */
    private static final long SETTLE_DELAY_MS = 4_000;

    private PaymentApiServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void startServer() throws Exception {
        PaymentService service = new PaymentService(new InMemoryPaymentRepository());
        // port 0 → ephemeral bind; no probe-close-rebind race.
        server = new PaymentApiServer(0, service, SETTLE_DELAY_MS, null, JOB_RETENTION);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop();
    }

    @Test
    @Story("Job store stays capped once deferred settlement has run")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("[ENDURANCE] Settling an evicted job must not put it back into the map")
    void settlementDoesNotResurrectEvictedJobs() throws Exception {
        for (int i = 0; i < PAYMENTS; i++) {
            post("ORDER-JOB-" + i, "IDEM-JOB-" + i);
        }

        // Every settlement timer is scheduled at accept + SETTLE_DELAY_MS, so once that has
        // elapsed since the last accept they have all fired. The margin absorbs scheduler jitter
        // on a loaded CI runner; it does not change the outcome, only how long a failure takes.
        Thread.sleep(SETTLE_DELAY_MS + 1_500);

        assertTrue(server.liveJobCount() <= JOB_RETENTION,
            "jobs holds " + server.liveJobCount() + " entries after " + PAYMENTS
          + " payments with retention " + JOB_RETENTION + ". Entries above the cap have no "
          + "deque record, so nothing can ever evict them and the map grows with request volume.");
    }

    private void post(String orderId, String idempotencyKey) throws Exception {
        String body = """
                {
                    "order_id": "%s",
                    "user_id": "USER_JOB_ENDURANCE",
                    "amount": 1.00,
                    "currency": "USDT",
                    "idempotency_key": "%s"
                }
                """.formatted(orderId, idempotencyKey);

        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + server.getPort() + "/api/v1/payments"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 202) {
            throw new IllegalStateException(
                    "Expected 202 for a fresh payment, got " + resp.statusCode() + ": " + resp.body());
        }
    }
}
