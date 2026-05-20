package com.binance.payment.integration;

import com.binance.payment.api.PaymentApiServer;
import com.binance.payment.service.InMemoryPaymentRepository;
import com.binance.payment.service.PaymentService;
import io.qameta.allure.*;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.net.ServerSocket;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end payment flow against the <b>real</b> {@link PaymentApiServer}.
 *
 * <p>P2: previously this was a pseudo-integration — WireMock returned canned
 * JSON while the test itself ran {@code DatabaseUtil.deductBalance(...)} to
 * "simulate the async worker". Now the <b>system</b> performs the deduction
 * (inside {@code PaymentService → InMemoryPaymentRepository.createPayment}); the
 * test only asserts the resulting balance via {@code repo.getBalance()}.</p>
 *
 * <p>A DB-backed repository (so persistence is exercised through real JDBC/H2)
 * is the next increment (P3); the {@code PaymentRepository} seam keeps that a
 * drop-in swap.</p>
 */
@Epic("Payment System")
@Feature("End-to-End Payment Flow")
class PaymentFlowTest {

    private PaymentApiServer server;
    private InMemoryPaymentRepository repo;

    @BeforeEach
    void startServer() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        repo = new InMemoryPaymentRepository();
        server = new PaymentApiServer(port, new PaymentService(repo), 20);
        server.start();

        RestAssured.baseURI = "http://localhost";
        RestAssured.port = port;
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop();
    }

    @Test
    @Story("Full Payment Flow")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Full flow: submit payment → 202 → system deducts balance → poll SUCCESS → verify balance")
    void full_payment_flow_api_202_to_balance_verified() throws InterruptedException {
        String userId = "USER_FLOW_001";
        repo.seedAccount(userId, new BigDecimal("500.00"));

        // Step 1: Submit payment → 202 Accepted (the service deducts here)
        String jobId = given()
            .contentType(ContentType.JSON)
            .body("""
                    {
                        "order_id": "ORD_FLOW_001",
                        "user_id": "%s",
                        "amount": 150.00,
                        "currency": "USDT",
                        "idempotency_key": "IDEM_FLOW_001"
                    }
                    """.formatted(userId))
        .when()
            .post("/api/v1/payments")
        .then()
            .statusCode(202)
            .body("status", equalTo("PENDING"))
            .extract()
            .path("job_id");

        // Step 2: Poll status → real async worker settles it to SUCCESS
        awaitSuccess(jobId);

        // Step 3: Verify the SYSTEM deducted the balance exactly once
        assertEquals(0, new BigDecimal("350.00").compareTo(repo.getBalance(userId)),
                "Balance should be 350.00 after a 150.00 payment");
    }

    @Test
    @Story("Full Payment Flow — Idempotency Guard")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Retrying a payment with same idempotency_key does not double-deduct balance")
    void retry_payment_does_not_double_charge() {
        String userId  = "USER_FLOW_002";
        String idemKey = "IDEM_FLOW_RETRY_001";
        repo.seedAccount(userId, new BigDecimal("1000.00"));

        // First payment — accepted and deducted (1000 → 800)
        given()
            .contentType(ContentType.JSON)
            .body(buildBody("ORD_RETRY_001", userId, "200.00", idemKey))
        .when()
            .post("/api/v1/payments")
        .then().statusCode(202);

        // Retry with the same idempotency_key — replay (200), NO second deduction
        given()
            .contentType(ContentType.JSON)
            .body(buildBody("ORD_RETRY_001", userId, "200.00", idemKey))
        .when()
            .post("/api/v1/payments")
        .then().statusCode(200);

        // Balance deducted exactly once despite the retry
        assertEquals(0, new BigDecimal("800.00").compareTo(repo.getBalance(userId)),
                "Balance should be 800.00 — deducted only once despite retry");
    }

    private void awaitSuccess(String jobId) throws InterruptedException {
        for (int attempt = 0; attempt < 40; attempt++) {
            String status = given()
                .when()
                    .get("/api/v1/payments/{jobId}/status", jobId)
                .then()
                    .statusCode(200)
                    .extract().path("status");
            if ("SUCCESS".equals(status)) return;
            Thread.sleep(25);
        }
        fail("job " + jobId + " did not settle to SUCCESS within timeout");
    }

    private String buildBody(String orderId, String userId, String amount, String idemKey) {
        return """
                {
                    "order_id": "%s",
                    "user_id": "%s",
                    "amount": %s,
                    "currency": "USDT",
                    "idempotency_key": "%s"
                }
                """.formatted(orderId, userId, amount, idemKey);
    }
}
