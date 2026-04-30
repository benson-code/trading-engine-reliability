package com.binance.payment.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.qameta.allure.*;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@Epic("Payment API")
@Feature("Payment Processing")
class PaymentAPITest {

    private static WireMockServer wireMockServer;

    @BeforeAll
    static void startMockServer() {
        wireMockServer = new WireMockServer(wireMockConfig().port(8089));
        wireMockServer.start();

        RestAssured.baseURI = "http://localhost";
        RestAssured.port = 8089;

        // Priority 1: negative amount → 400 (matched first)
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/payments"))
                .withRequestBody(containing("\"amount\": -"))
                .atPriority(1)
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "error": "INVALID_AMOUNT",
                                    "message": "Amount must be positive"
                                }
                                """)));

        // Priority 5: all valid payments → 202 Accepted (async flow)
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/payments"))
                .atPriority(5)
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "payment_id": "PAY_20260428_001",
                                    "status": "PENDING",
                                    "job_id": "JOB_20260428_001",
                                    "message": "Payment accepted. Use job_id to poll status."
                                }
                                """)));

        // Status polling endpoint
        wireMockServer.stubFor(get(urlMatching("/api/v1/payments/.*/status"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "payment_id": "PAY_20260428_001",
                                    "status": "SUCCESS",
                                    "message": "Payment completed"
                                }
                                """)));
    }

    @AfterAll
    static void stopMockServer() {
        wireMockServer.stop();
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
    void async_payment_polling_returns_success() {
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

        // Step 2: Poll status with job_id → confirm final state
        given()
        .when()
            .get("/api/v1/payments/{jobId}/status", jobId)
        .then()
            .statusCode(200)
            .body("status", equalTo("SUCCESS"));
    }
}
