package com.binance.payment.api;

import com.binance.payment.service.InMemoryPaymentRepository;
import com.binance.payment.service.PaymentService;
import io.qameta.allure.*;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import java.net.ServerSocket;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Payment API tests against the <b>real</b> {@link PaymentApiServer}.
 *
 * <p>P2: previously these asserted against hard-coded WireMock JSON and never
 * executed the service. They now exercise the actual validation and
 * async-settlement code paths. Each test uses a distinct idempotency key, so a
 * single shared server is safe.</p>
 */
@Epic("Payment API")
@Feature("Payment Processing")
class PaymentAPITest {

    private static PaymentApiServer server;

    @BeforeAll
    static void startServer() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        PaymentService service = new PaymentService(new InMemoryPaymentRepository());
        server = new PaymentApiServer(port, service, 20);  // 20 ms async settle
        server.start();

        RestAssured.baseURI = "http://localhost";
        RestAssured.port = port;
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop();
    }

    // ─── Happy Path ─────────────────────────────────────────────────────────

    @Test
    @Story("Happy Path")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("POST /api/v1/payments returns 202 Accepted for valid payment request")
    void payment_happy_path_returns_202() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {
                        "order_id": "ORD_001",
                        "user_id": "USER_001",
                        "amount": 100.00,
                        "currency": "USDT",
                        "idempotency_key": "IDEM_HAPPY_001"
                    }
                    """)
        .when()
            .post("/api/v1/payments")
        .then()
            .statusCode(202)
            .body("status", equalTo("PENDING"))
            .body("job_id", notNullValue())
            .body("message", containsString("poll"));
    }

    // ─── Negative Cases ──────────────────────────────────────────────────────

    @Test
    @Story("Negative Case")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("POST /api/v1/payments returns 400 for negative amount")
    void payment_negative_amount_returns_400() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {
                        "order_id": "ORD_002",
                        "user_id": "USER_001",
                        "amount": -100,
                        "currency": "USDT",
                        "idempotency_key": "IDEM_NEG_001"
                    }
                    """)
        .when()
            .post("/api/v1/payments")
        .then()
            .statusCode(400)
            .body("error", equalTo("INVALID_AMOUNT"));
    }

    // ─── Async Flow ──────────────────────────────────────────────────────────

    @Test
    @Story("Async Payment Flow")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Payment returns job_id; polling the status endpoint returns SUCCESS")
    void async_payment_polling_returns_success() throws InterruptedException {
        // Step 1: Submit payment → get job_id
        String jobId = given()
            .contentType(ContentType.JSON)
            .body("""
                    {
                        "order_id": "ORD_003",
                        "user_id": "USER_001",
                        "amount": 50.00,
                        "currency": "USDT",
                        "idempotency_key": "IDEM_ASYNC_001"
                    }
                    """)
        .when()
            .post("/api/v1/payments")
        .then()
            .statusCode(202)
            .extract()
            .path("job_id");

        // Step 2: Poll status with job_id → real async worker settles it
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
}
