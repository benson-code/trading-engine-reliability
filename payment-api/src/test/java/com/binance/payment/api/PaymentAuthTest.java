package com.binance.payment.api;

import com.binance.payment.service.InMemoryPaymentRepository;
import com.binance.payment.service.PaymentService;
import io.qameta.allure.*;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * P4 / audit finding A2 — {@code X-API-Key} authentication.
 *
 * <p>This server is started <b>with</b> an API key, so the payment endpoints
 * require a valid {@code X-API-Key}; the health endpoint stays exempt
 * (readiness probes must not need credentials). All other test classes start
 * the server without a key, exercising the auth-disabled path.</p>
 */
@Epic("Payment API")
@Feature("Authentication — X-API-Key")
class PaymentAuthTest {

    private static final String API_KEY = "test-secret-key-123";
    private static PaymentApiServer server;

    @BeforeAll
    static void startSecuredServer() throws Exception {
        PaymentService service = new PaymentService(new InMemoryPaymentRepository());
        server = new PaymentApiServer(0, service, 20, API_KEY);
        server.start();
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = server.getPort();
    }

    @AfterAll
    static void stopSecuredServer() {
        if (server != null) server.stop();
    }

    private String body(String idemKey) {
        return """
                {
                    "order_id": "ORD_AUTH",
                    "user_id": "USER_AUTH",
                    "amount": 10.00,
                    "currency": "USDT",
                    "idempotency_key": "%s"
                }
                """.formatted(idemKey);
    }

    @Test
    @Story("Health is exempt from auth")
    @DisplayName("GET /api/v1/health needs no API key")
    void health_is_exempt() {
        given()
        .when()
            .get("/api/v1/health")
        .then()
            .statusCode(200)
            .body("status", equalTo("UP"));
    }

    @Test
    @Story("Auth required")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("POST without X-API-Key returns 401 UNAUTHORIZED")
    void missing_api_key_rejected() {
        given()
            .contentType(ContentType.JSON)
            .body(body("AUTH_MISSING"))
        .when()
            .post("/api/v1/payments")
        .then()
            .statusCode(401)
            .body("error", equalTo("UNAUTHORIZED"));
    }

    @Test
    @Story("Auth required")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("POST with a wrong X-API-Key returns 401 UNAUTHORIZED")
    void wrong_api_key_rejected() {
        given()
            .contentType(ContentType.JSON)
            .header("X-API-Key", "not-the-real-key")
            .body(body("AUTH_WRONG"))
        .when()
            .post("/api/v1/payments")
        .then()
            .statusCode(401)
            .body("error", equalTo("UNAUTHORIZED"));
    }

    @Test
    @Story("Auth required")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("POST with the correct X-API-Key is accepted (202)")
    void valid_api_key_accepted() {
        given()
            .contentType(ContentType.JSON)
            .header("X-API-Key", API_KEY)
            .body(body("AUTH_OK"))
        .when()
            .post("/api/v1/payments")
        .then()
            .statusCode(202);
    }

    @Test
    @Story("Auth required")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("GET status without X-API-Key returns 401 UNAUTHORIZED")
    void status_requires_auth() {
        // Create a payment with a valid key, then poll its status WITHOUT one.
        String jobId = given()
            .contentType(ContentType.JSON)
            .header("X-API-Key", API_KEY)
            .body(body("AUTH_STATUS"))
        .when()
            .post("/api/v1/payments")
        .then()
            .statusCode(202)
            .extract().path("job_id");

        given()
        .when()
            .get("/api/v1/payments/{jobId}/status", jobId)
        .then()
            .statusCode(401)
            .body("error", equalTo("UNAUTHORIZED"));
    }
}
