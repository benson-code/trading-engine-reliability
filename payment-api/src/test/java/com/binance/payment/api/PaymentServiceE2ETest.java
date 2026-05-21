package com.binance.payment.api;

import com.binance.payment.service.InMemoryPaymentRepository;
import com.binance.payment.service.PaymentService;
import io.qameta.allure.*;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end test against the <b>real</b> {@link PaymentApiServer} + the real
 * {@link PaymentService} + {@link InMemoryPaymentRepository} — no WireMock.
 *
 * <p>This is the P1 proof: the previous API tests asserted against hard-coded
 * WireMock JSON and never executed the service. These exercise the actual
 * validation, idempotency and async-settlement code paths.</p>
 */
@Epic("Payment API")
@Feature("Real Service — End-to-End (no WireMock)")
class PaymentServiceE2ETest {

    private static PaymentApiServer server;

    @BeforeAll
    static void startRealServer() throws Exception {
        // port 0 → ephemeral bind; no probe-close-rebind race.
        PaymentService service =
                new PaymentService(new InMemoryPaymentRepository());
        server = new PaymentApiServer(0, service, 30);  // 30 ms settle
        server.start();

        RestAssured.baseURI = "http://localhost";
        RestAssured.port = server.getPort();
    }

    @AfterAll
    static void stopRealServer() {
        if (server != null) server.stop();
    }

    private String body(String orderId, String idemKey, String amount) {
        return """
                {
                    "order_id": "%s",
                    "user_id": "USER_E2E",
                    "amount": %s,
                    "currency": "USDT",
                    "idempotency_key": "%s"
                }
                """.formatted(orderId, amount, idemKey);
    }

    // ─── Health ──────────────────────────────────────────────────────────────

    @Test
    @Story("Readiness")
    @DisplayName("GET /api/v1/health returns 200 UP")
    void health_returns_up() {
        given()
        .when()
            .get("/api/v1/health")
        .then()
            .statusCode(200)
            .body("status", equalTo("UP"));
    }

    // ─── Happy Path ──────────────────────────────────────────────────────────

    @Test
    @Story("Happy Path")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("POST /api/v1/payments returns 202 with payment_id, PENDING, job_id")
    void happy_path_returns_202() {
        given()
            .contentType(ContentType.JSON)
            .body(body("ORD_E2E_1", "IDEM_E2E_HAPPY", "100.00"))
        .when()
            .post("/api/v1/payments")
        .then()
            .statusCode(202)
            .body("payment_id", notNullValue())
            .body("status", equalTo("PENDING"))
            .body("job_id", notNullValue())
            .body("message", containsString("poll"));
    }

    // ─── Negative Case ───────────────────────────────────────────────────────

    @Test
    @Story("Negative Case")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("POST with negative amount returns 400 INVALID_AMOUNT (real validation)")
    void negative_amount_returns_400() {
        given()
            .contentType(ContentType.JSON)
            .body(body("ORD_E2E_NEG", "IDEM_E2E_NEG", "-100"))
        .when()
            .post("/api/v1/payments")
        .then()
            .statusCode(400)
            .body("error", equalTo("INVALID_AMOUNT"));
    }

    @Test
    @Story("Amount precision — D3 regression guard")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Amount with 9 decimals returns 400 INVALID_PRECISION (not silently truncated)")
    void overprecise_amount_returns_400() {
        // Pre-fix this was accepted as 202 and the DB silently truncated to 8
        // decimals (DECIMAL(18,8)).
        given()
            .contentType(ContentType.JSON)
            .body(body("ORD_E2E_PREC", "IDEM_E2E_PREC", "0.123456789"))
        .when()
            .post("/api/v1/payments")
        .then()
            .statusCode(400)
            .body("error", equalTo("INVALID_PRECISION"));
    }

    @Test
    @Story("Amount precision — trailing zeros accepted")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("Amount 100.500000000 (= 100.5) is accepted, not over-rejected")
    void trailing_zero_amount_accepted() {
        given()
            .contentType(ContentType.JSON)
            .body(body("ORD_E2E_TZ", "IDEM_E2E_TZ", "100.500000000"))
        .when()
            .post("/api/v1/payments")
        .then()
            .statusCode(202);
    }

    @Test
    @Story("Currency mismatch — D1 regression guard")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Paying BTC against a USDT account returns 422 CURRENCY_MISMATCH")
    void currency_mismatch_returns_422() {
        // USER_E2E is auto-provisioned in the default currency (USDT); a BTC
        // payment must be rejected (pre-fix it was silently accepted as 202).
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {
                        "order_id": "ORD_E2E_CCY",
                        "user_id": "USER_E2E",
                        "amount": 1.5,
                        "currency": "BTC",
                        "idempotency_key": "IDEM_E2E_CCY"
                    }
                    """)
        .when()
            .post("/api/v1/payments")
        .then()
            .statusCode(422)
            .body("error", equalTo("CURRENCY_MISMATCH"));
    }

    @Test
    @Story("HTTP code accuracy — D2 regression guard")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Over-length idempotency key returns 400 VALIDATION_ERROR (not 402 / 500)")
    void overlong_idempotency_key_returns_400_not_402() {
        // Pre-fix this surfaced as 402 INSUFFICIENT_BALANCE because the SQL
        // truncation got caught as a generic IllegalStateException and mapped
        // to 402 — clearly the wrong code. Service-layer length validation now
        // catches it cleanly.
        given()
            .contentType(ContentType.JSON)
            .body(body("ORD_E2E_LONG", "K".repeat(101), "10.00"))
        .when()
            .post("/api/v1/payments")
        .then()
            .statusCode(400)
            .body("error", equalTo("VALIDATION_ERROR"))
            .body("message", containsString("Idempotency key too long"));
    }

    // ─── Idempotency ─────────────────────────────────────────────────────────

    @Test
    @Story("Idempotency")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Same idempotency key twice → same payment_id, retry answers 200")
    void duplicate_key_returns_same_payment_id() {
        String b = body("ORD_E2E_IDEM", "IDEM_E2E_DUP", "250.00");

        Response first = given()
            .contentType(ContentType.JSON).body(b)
        .when()
            .post("/api/v1/payments")
        .then()
            .statusCode(202)
            .extract().response();

        Response retry = given()
            .contentType(ContentType.JSON).body(b)
        .when()
            .post("/api/v1/payments")
        .then()
            .statusCode(200)   // replay, not a second accept
            .extract().response();

        assertEquals(
            first.path("payment_id").toString(),
            retry.path("payment_id").toString(),
            "duplicate idempotency key must not create a second payment");
    }

    // ─── Async Settlement ────────────────────────────────────────────────────

    @Test
    @Story("Async Payment Flow")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("job_id polls PENDING → SUCCESS on the real status endpoint")
    void async_job_settles_to_success() throws InterruptedException {
        String jobId = given()
            .contentType(ContentType.JSON)
            .body(body("ORD_E2E_ASYNC", "IDEM_E2E_ASYNC", "75.00"))
        .when()
            .post("/api/v1/payments")
        .then()
            .statusCode(202)
            .extract().path("job_id");

        // Poll until the settler flips the job (30 ms delay → settles fast).
        for (int attempt = 0; attempt < 40; attempt++) {
            String status = given()
                .when()
                    .get("/api/v1/payments/{jobId}/status", jobId)
                .then()
                    .statusCode(200)
                    .body("status", anyOf(equalTo("PENDING"), equalTo("SUCCESS")))
                    .extract().path("status");
            if ("SUCCESS".equals(status)) return;
            Thread.sleep(25);
        }
        fail("job " + jobId + " did not settle to SUCCESS within timeout");
    }
}
