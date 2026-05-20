package com.binance.payment.api;

import com.binance.payment.service.InMemoryPaymentRepository;
import com.binance.payment.service.PaymentService;
import io.qameta.allure.*;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Idempotency tests against the <b>real</b> {@link PaymentApiServer}.
 *
 * <p>P2: replaces WireMock scenario stubs with the actual idempotency guarantee
 * of {@link PaymentService} + {@link InMemoryPaymentRepository}. A fresh server
 * + repository is created per test because both tests reuse the same
 * idempotency key — per-test isolation removes any ordering hazard.</p>
 */
@Epic("Payment API")
@Feature("Idempotency — Duplicate Payment Prevention")
class IdempotencyTest {

    private static final String IDEMPOTENCY_KEY = "IDEM-FIXED-KEY-001";

    private PaymentApiServer server;

    @BeforeEach
    void startServer() throws Exception {
        PaymentService service = new PaymentService(new InMemoryPaymentRepository());
        server = new PaymentApiServer(0, service, 20);
        server.start();

        RestAssured.baseURI = "http://localhost";
        RestAssured.port = server.getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop();
    }

    @Test
    @Story("Idempotency")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("First payment request with new idempotency key returns 202")
    void first_payment_creates_new_record() {
        given()
            .contentType(ContentType.JSON)
            .body(buildPaymentBody("ORD_IDEM_001"))
        .when()
            .post("/api/v1/payments")
        .then()
            .statusCode(202)
            .body("payment_id", notNullValue())
            .body("job_id", notNullValue());
    }

    @Test
    @Story("Idempotency")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Duplicate request with same idempotency key returns same payment_id — no double charge")
    void duplicate_idempotency_key_returns_same_response() {
        String requestBody = buildPaymentBody("ORD_IDEM_001");

        // First call → new payment accepted (202)
        Response firstResponse = given()
            .contentType(ContentType.JSON)
            .body(requestBody)
        .when()
            .post("/api/v1/payments")
        .then()
            .statusCode(202)
            .extract().response();

        // Second call (client retry) → idempotent replay, not a second accept
        Response secondResponse = given()
            .contentType(ContentType.JSON)
            .body(requestBody)
        .when()
            .post("/api/v1/payments")
        .then()
            .statusCode(200)
            .extract().response();

        // Same payment_id → the service did NOT create a second payment.
        // (PaymentService.processPayment short-circuits on findByIdempotencyKey,
        //  so repository.createPayment — and its balance deduction — runs once.)
        assertEquals(
            firstResponse.path("payment_id").toString(),
            secondResponse.path("payment_id").toString(),
            "payment_id must be identical for duplicate idempotency key");
    }

    private String buildPaymentBody(String orderId) {
        return """
                {
                    "order_id": "%s",
                    "user_id": "USER_001",
                    "amount": 100.00,
                    "currency": "USDT",
                    "idempotency_key": "%s"
                }
                """.formatted(orderId, IDEMPOTENCY_KEY);
    }
}
