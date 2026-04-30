package com.binance.payment.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.qameta.allure.*;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;

import com.github.tomakehurst.wiremock.client.WireMock;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Epic("Payment API")
@Feature("Idempotency — Duplicate Payment Prevention")
class IdempotencyTest {

    private static WireMockServer wireMockServer;
    private static final String IDEMPOTENCY_KEY = "IDEM-FIXED-KEY-001";
    private static final String SCENARIO_NAME   = "DuplicatePaymentScenario";

    @BeforeAll
    static void startMockServer() {
        wireMockServer = new WireMockServer(wireMockConfig().port(8090));
        wireMockServer.start();
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = 8090;

        // State 1: first call → create new payment (202)
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/payments"))
                .inScenario(SCENARIO_NAME)
                .whenScenarioStateIs(Scenario.STARTED)
                .withHeader("Idempotency-Key", WireMock.equalTo(IDEMPOTENCY_KEY))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "payment_id": "PAY_IDEM_001",
                                    "status": "PENDING",
                                    "job_id": "JOB_IDEM_001"
                                }
                                """))
                .willSetStateTo("PAYMENT_CREATED"));

        // State 2: subsequent calls with same key → return same response (200, not 201/202 again)
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/payments"))
                .inScenario(SCENARIO_NAME)
                .whenScenarioStateIs("PAYMENT_CREATED")
                .withHeader("Idempotency-Key", WireMock.equalTo(IDEMPOTENCY_KEY))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "payment_id": "PAY_IDEM_001",
                                    "status": "PENDING",
                                    "job_id": "JOB_IDEM_001"
                                }
                                """)));
    }

    @AfterAll
    static void stopMockServer() {
        wireMockServer.stop();
    }

    @BeforeEach
    void resetScenario() {
        // Each test starts from STARTED state to ensure test isolation
        wireMockServer.resetScenarios();
    }

    @Test
    @Story("Idempotency")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("First payment request with new idempotency key returns 202")
    void first_payment_creates_new_record() {
        given()
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", IDEMPOTENCY_KEY)
            .body(buildPaymentBody("ORD_IDEM_001"))
        .when()
            .post("/api/v1/payments")
        .then()
            .statusCode(202)
            .body("payment_id", equalTo("PAY_IDEM_001"))
            .body("job_id", notNullValue());
    }

    @Test
    @Story("Idempotency")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("Duplicate request with same idempotency key returns same payment_id — no double charge")
    void duplicate_idempotency_key_returns_same_response() {
        String requestBody = buildPaymentBody("ORD_IDEM_001");

        // First call
        Response firstResponse = given()
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", IDEMPOTENCY_KEY)
            .body(requestBody)
        .when()
            .post("/api/v1/payments")
        .then()
            .statusCode(anyOf(equalTo(200), equalTo(202)))
            .extract().response();

        // Second call (retry simulation)
        Response secondResponse = given()
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", IDEMPOTENCY_KEY)
            .body(requestBody)
        .when()
            .post("/api/v1/payments")
        .then()
            .statusCode(anyOf(equalTo(200), equalTo(202)))
            .extract().response();

        // Both must return the same payment_id → no duplicate payment created
        assertEquals(
            firstResponse.path("payment_id").toString(),
            secondResponse.path("payment_id").toString(),
            "payment_id must be identical for duplicate idempotency key"
        );

        // Verify API was called exactly twice (no silent deduplication before network)
        wireMockServer.verify(2, postRequestedFor(urlEqualTo("/api/v1/payments"))
                .withHeader("Idempotency-Key", WireMock.equalTo(IDEMPOTENCY_KEY)));
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
