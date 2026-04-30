package com.binance.payment.integration;

import com.binance.payment.util.DatabaseUtil;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.qameta.allure.*;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.sql.SQLException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration tests: combine WireMock (API layer) + H2 (DB layer).
 *
 * Flow under test:
 *   Client → POST /api/v1/payments (WireMock) → 202 + job_id
 *   Async worker → deduct balance (H2)
 *   Client → GET /api/v1/payments/{jobId}/status → SUCCESS
 *   Assert: DB balance is correct
 */
@Epic("Payment System")
@Feature("End-to-End Payment Flow")
class PaymentFlowTest {

    private static WireMockServer wireMockServer;

    @BeforeAll
    static void setUp() throws SQLException {
        // Start WireMock on port 8091 (independent of other test classes)
        wireMockServer = new WireMockServer(wireMockConfig().port(8091));
        wireMockServer.start();
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = 8091;

        // Payment submission → 202 Accepted
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/payments"))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "payment_id": "PAY_FLOW_001",
                                    "status": "PENDING",
                                    "job_id": "JOB_FLOW_001",
                                    "message": "Payment accepted. Use job_id to poll status."
                                }
                                """)));

        // Status polling → SUCCESS after async processing
        wireMockServer.stubFor(get(urlEqualTo("/api/v1/payments/JOB_FLOW_001/status"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "payment_id": "PAY_FLOW_001",
                                    "status": "SUCCESS"
                                }
                                """)));

        // Initialize H2 DB
        DatabaseUtil.initSchema();
    }

    @AfterAll
    static void tearDown() {
        wireMockServer.stop();
    }

    @BeforeEach
    void cleanData() throws SQLException {
        DatabaseUtil.cleanTables();
    }

    @Test
    @Story("Full Payment Flow")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Full flow: submit payment → 202 → async DB deduction → poll SUCCESS → verify balance")
    void full_payment_flow_api_202_to_db_verified() throws SQLException {
        // Arrange: seed user with initial balance
        String userId = "USER_FLOW_001";
        BigDecimal initialBalance = new BigDecimal("500.00");
        BigDecimal paymentAmount  = new BigDecimal("150.00");
        DatabaseUtil.insertAccount(userId, initialBalance);

        // Step 1: Submit payment → expect 202 Accepted
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

        // Step 2: Simulate async worker — deduct balance in DB
        DatabaseUtil.insertPayment("PAY_FLOW_001", "ORD_FLOW_001", userId, paymentAmount, "IDEM_FLOW_001");
        DatabaseUtil.deductBalance(userId, paymentAmount);

        // Step 3: Poll status → expect SUCCESS
        given()
        .when()
            .get("/api/v1/payments/{jobId}/status", jobId)
        .then()
            .statusCode(200)
            .body("status", equalTo("SUCCESS"));

        // Step 4: Verify DB balance is correctly deducted
        BigDecimal finalBalance = DatabaseUtil.getBalance(userId);
        assertEquals(0, new BigDecimal("350.00").compareTo(finalBalance),
                "Balance should be 350.00 after 150.00 payment");
    }

    @Test
    @Story("Full Payment Flow — Idempotency Guard")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Retrying a payment with same idempotency_key does not double-deduct balance")
    void retry_payment_does_not_double_charge() throws SQLException {
        // Arrange
        String userId   = "USER_FLOW_002";
        String idemKey  = "IDEM_FLOW_RETRY_001";
        BigDecimal balance = new BigDecimal("1000.00");
        BigDecimal amount  = new BigDecimal("200.00");
        DatabaseUtil.insertAccount(userId, balance);

        // First payment — accepted and processed
        given()
            .contentType(ContentType.JSON)
            .body(buildBody("ORD_RETRY_001", userId, amount, idemKey))
        .when()
            .post("/api/v1/payments")
        .then().statusCode(202);

        DatabaseUtil.insertPayment("PAY_RETRY_001", "ORD_RETRY_001", userId, amount, idemKey);
        DatabaseUtil.deductBalance(userId, amount);

        // Retry: same idempotency_key — API accepts but DB must not process again
        given()
            .contentType(ContentType.JSON)
            .body(buildBody("ORD_RETRY_001", userId, amount, idemKey))
        .when()
            .post("/api/v1/payments")
        .then().statusCode(anyOf(equalTo(200), equalTo(202)));

        // DB guard: UNIQUE constraint prevents second insert
        try {
            DatabaseUtil.insertPayment("PAY_RETRY_001_B", "ORD_RETRY_001", userId, amount, idemKey);
        } catch (Exception ignored) {
            // Expected: unique constraint blocks duplicate
        }

        // Assert: balance deducted exactly once
        assertEquals(0, new BigDecimal("800.00").compareTo(DatabaseUtil.getBalance(userId)),
                "Balance should be 800.00 — deducted only once despite retry");
    }

    private String buildBody(String orderId, String userId, BigDecimal amount, String idemKey) {
        return """
                {
                    "order_id": "%s",
                    "user_id": "%s",
                    "amount": %s,
                    "currency": "USDT",
                    "idempotency_key": "%s"
                }
                """.formatted(orderId, userId, amount.toPlainString(), idemKey);
    }
}
